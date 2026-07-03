package com.dvdutch.recall.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dvdutch.recall.api.BridgeClient
import com.dvdutch.recall.api.ImageNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/** The path prefix every bridge media `src` carries: `/v1/media/<url-encoded-name>`. */
private const val MEDIA_PATH_PREFIX = "/v1/media/"

/** Default session capacity: a session's worth of images, not a durable cache (M2 owns that). */
private const val DEFAULT_MAX_ENTRIES = 16

/**
 * Extracts the media filename from an [ImageNode] `src`.
 *
 * As of the Kotlin render-compiler port, `src` carries the BARE, percent-DECODED
 * media filename (e.g. `map europe.webp`) rather than a `/v1/media/...` URL. This
 * function returns that name directly. For backward compatibility with any
 * `/v1/media/<url-encoded-name>` src still in flight (older bridge payloads /
 * fixtures), a leading `/v1/media/` prefix is stripped and the remainder
 * percent-decoded. Returns null when the src is empty or a non-media absolute URL
 * — the caller then renders the placeholder rather than firing a doomed request.
 *
 * Pure and unit-tested.
 */
fun mediaFilenameFromSrc(src: String): String? {
    if (src.startsWith(MEDIA_PATH_PREFIX)) {
        val encoded = src.substring(MEDIA_PATH_PREFIX.length)
        if (encoded.isEmpty()) return null
        // URLDecoder turns "+" into a space; media names use %20 for spaces and
        // never contain literal "+", but guard by protecting any "+" first.
        val name = URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
        return name.ifEmpty { null }
    }
    // Bare filename contract. Reject non-media absolute URLs / paths.
    if (src.isEmpty()) return null
    if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("/")) return null
    return src
}

/**
 * A tiny insertion/access-ordered LRU map. Thread-safe: [MediaLoader.load] now runs on
 * [Dispatchers.Default], so concurrent [MediaImage] producers can genuinely parallelise
 * and race on the map — Main-confinement no longer holds. Access-order mutates the map
 * even on reads (`get`), so every operation, `get` included, is synchronized on the map.
 * Generic (and unit-tested) over value type so eviction order can be asserted without
 * constructing Android bitmaps.
 */
class SessionLru<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = synchronized(map) { map[key] }

    fun put(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }
}

/**
 * Loads and decodes bridge media images for one session.
 *
 * [load] extracts the filename from a `/v1/media/...` src, fetches the bytes via
 * [BridgeClient.media], and decodes them to an [ImageBitmap]. Decoded images are
 * held in a small [SessionLru] (~16 entries) keyed by filename, so re-rendering the
 * same card doesn't refetch. Any failure — a non-media src, a transport/bridge
 * error, or an undecodable body — resolves to null, and the composable falls back
 * to the labelled placeholder. Nothing is ever silently dropped: a null means "show
 * the placeholder", not "show nothing".
 *
 * This is a session buffer, not a cache layer; durable caching is M2's concern.
 *
 * @param decode injectable byte→bitmap step; production uses [BitmapFactory],
 *   tests substitute a pure function (the real decoder needs the Android runtime).
 */
class MediaLoader(
    private val client: BridgeClient,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
) {

    private val cache = SessionLru<String, ImageBitmap>(maxEntries)

    suspend fun load(src: String): ImageBitmap? {
        val name = mediaFilenameFromSrc(src) ?: return null
        // Move fetch + decode off the caller's dispatcher: MediaImage's produceState
        // producer runs on the composition's Main context, and a full-res WebP decode
        // (BitmapFactory) inline there janks frames. Dispatchers.Default makes the
        // loader off-main-safe at the source rather than at each call site.
        return withContext(Dispatchers.Default) {
            cache.get(name)?.let { return@withContext it }
            val bytes = try {
                client.media(name)
            } catch (e: Exception) {
                // Cancellation must propagate — swallowing it into a null would mask a
                // cancelled load as a genuine failure (and fight structured concurrency).
                if (e is CancellationException) throw e
                // BridgeError (Unreachable/Unauthorized/…) and any other transport
                // failure → placeholder. Content is preserved as a labelled fallback.
                return@withContext null
            }
            val bitmap = decode(bytes) ?: return@withContext null
            cache.put(name, bitmap)
            bitmap
        }
    }
}

/** Production byte→bitmap decode. Android-runtime only; emulator-verified. */
private fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * Renders an [ImageNode]: the decoded image once [loader] resolves it, otherwise
 * the shared labelled placeholder (both while loading and on any failure). When the
 * node declares intrinsic `w`/`h`, the image fills the available width and keeps
 * that aspect ratio; otherwise it wraps its content.
 */
@Composable
fun MediaImage(node: ImageNode, loader: MediaLoader) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, node.src, loader) {
        value = loader.load(node.src)
    }

    val image = bitmap
    if (image == null) {
        ImageNodePlaceholder(node)
        return
    }

    val w = node.w
    val h = node.h
    val modifier = if (w != null && h != null && w > 0 && h > 0) {
        Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h.toFloat())
    } else {
        Modifier.wrapContentSize()
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
