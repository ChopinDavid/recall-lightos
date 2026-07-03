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
import java.net.URLDecoder

/** The path prefix every bridge media `src` carries: `/v1/media/<url-encoded-name>`. */
private const val MEDIA_PATH_PREFIX = "/v1/media/"

/** Default session capacity: a session's worth of images, not a durable cache (M2 owns that). */
private const val DEFAULT_MAX_ENTRIES = 16

/**
 * Extracts the media filename from an [ImageNode] `src` of the form
 * `/v1/media/<url-encoded-name>`, reversing the percent-encoding
 * ([BridgeClient.media] re-encodes it on the way out). Returns null when the src
 * is not a media path or carries no name — the caller then renders the placeholder
 * rather than firing a doomed request.
 *
 * Pure and unit-tested. `+` is treated literally (it is not a space in a URL path
 * segment), matching how the bridge encodes names.
 */
fun mediaFilenameFromSrc(src: String): String? {
    if (!src.startsWith(MEDIA_PATH_PREFIX)) return null
    val encoded = src.substring(MEDIA_PATH_PREFIX.length)
    if (encoded.isEmpty()) return null
    // URLDecoder turns "+" into a space; media names use %20 for spaces and never
    // contain literal "+", but guard anyway by protecting any "+" before decoding.
    val name = URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
    return name.ifEmpty { null }
}

/**
 * A tiny insertion/access-ordered LRU map. Not thread-safe; [MediaLoader] confines
 * all access to the loading coroutine. Generic (and unit-tested) over value type so
 * eviction order can be asserted without constructing Android bitmaps.
 */
class SessionLru<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map[key] = value
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
        cache.get(name)?.let { return it }
        val bytes = try {
            client.media(name)
        } catch (_: Exception) {
            // BridgeError (Unreachable/Unauthorized/…) and any other transport
            // failure → placeholder. Content is preserved as a labelled fallback.
            return null
        }
        val bitmap = decode(bytes) ?: return null
        cache.put(name, bitmap)
        return bitmap
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
