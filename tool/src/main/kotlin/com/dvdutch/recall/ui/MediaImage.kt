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
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.prefs.RecallStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 * Loads and decodes on-device media images for one session, reading straight from
 * the collection's `collection.media` directory.
 *
 * [load] extracts the bare filename from an [ImageNode] src, reads the file's bytes
 * from disk (`<collection dir>/collection.media/<name>`), and decodes them to an
 * [ImageBitmap]. Decoded images are held in a small [SessionLru] (~16 entries) keyed
 * by filename, so re-rendering the same card doesn't re-read/decode. Any failure — a
 * non-media src, a missing file, or an undecodable body — resolves to null, and the
 * composable falls back to the labelled placeholder. Nothing is ever silently
 * dropped: a null means "show the placeholder", not "show nothing".
 *
 * This is a session buffer, not a cache layer; durable caching is M2's concern.
 *
 * @param mediaFileOf resolves a bare media name to its on-disk [File] (production
 *   uses [RecallStorage.mediaFile]); injectable so tests point it at a temp dir.
 * @param decode injectable byte→bitmap step; production uses [BitmapFactory],
 *   tests substitute a pure function (the real decoder needs the Android runtime).
 */
class MediaLoader(
    private val mediaFileOf: (String) -> File,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
) {

    /** Convenience: resolve media files from a [RecallStorage]. */
    constructor(
        storage: RecallStorage,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
    ) : this(storage::mediaFile, maxEntries, decode)

    private val cache = SessionLru<String, ImageBitmap>(maxEntries)

    /**
     * Synchronous, disk-free probe of the session cache. Returns the already-decoded
     * bitmap for [src] if a prior [load] cached it, else null — never reads a file or
     * decodes. This lets a composable seed its initial state with a cache hit so an
     * image already in memory renders on the first frame instead of flashing the
     * Loading placeholder (the front→back occlusion reveal swaps composable instances,
     * restarting [produceState] from its initial value; without this seed that initial
     * value is a null bitmap even for a just-loaded image). A miss returns null and the
     * caller falls back to the normal load path. Mirrors [load]'s filename extraction so
     * a `/v1/media/...` src probes the same key the bare name populated.
     */
    fun peek(src: String): ImageBitmap? {
        val name = mediaFilenameFromSrc(src) ?: return null
        return cache.get(name)
    }

    suspend fun load(src: String): ImageBitmap? {
        val name = mediaFilenameFromSrc(src) ?: return null
        // Move the file read + decode off the caller's dispatcher: MediaImage's
        // produceState producer runs on the composition's Main context, and a full-res
        // WebP decode (BitmapFactory) inline there janks frames. Dispatchers.Default
        // makes the loader off-main-safe at the source rather than at each call site.
        return withContext(Dispatchers.Default) {
            cache.get(name)?.let { return@withContext it }
            val bytes = try {
                val file = mediaFileOf(name)
                if (!file.isFile) return@withContext null // missing media → placeholder
                file.readBytes()
            } catch (e: Exception) {
                // Cancellation must propagate — swallowing it into a null would mask a
                // cancelled load as a genuine failure (and fight structured concurrency).
                if (e is CancellationException) throw e
                // Any I/O failure → placeholder. Content is preserved as a labelled fallback.
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
 * How a decoded `<img>` bitmap is sized on screen. This is the pure sizing decision;
 * the caller maps it to a Compose modifier.
 */
sealed interface ImageDisplayMode {
    /**
     * Scale the image to fill the available content width, keeping this [aspectRatio]
     * (width / height). A small image scales UP to the width and a large one scales
     * DOWN — matching Anki/AnkiDroid, where `<img>` is fit to the column width.
     */
    data class FitToWidth(val aspectRatio: Float) : ImageDisplayMode

    /** Draw the image at its intrinsic size (last-resort fallback for unmeasurable input). */
    data object Wrap : ImageDisplayMode
}

/**
 * Decides how to size an `<img>` node given the decoded bitmap's natural pixels
 * ([naturalW]/[naturalH]) and any explicit HTML `width`/`height` attributes
 * ([explicitW]/[explicitH], null when the source tag omitted them).
 *
 * Matches Anki/AnkiDroid: an `<img>` is fit to the available content width regardless
 * of its native size (a 32px element GIF scales UP so it is legible; a 2000px photo
 * scales DOWN). The only question is which aspect ratio to preserve while doing so:
 *   - explicit `width` AND `height` present (both > 0) → honour that intended aspect,
 *     so a deck author's `<img width=200 height=100>` renders 2:1 even if the bitmap
 *     isn't. It is still clamped into the width (never blown past screen edge).
 *   - otherwise → the bitmap's own natural aspect ratio.
 * Degenerate natural dimensions (≤ 0, e.g. an unmeasurable decode) can't yield an
 * aspect, so we fall back to [ImageDisplayMode.Wrap] rather than dividing by zero.
 *
 * Pure and unit-tested.
 */
fun imageDisplayMode(
    naturalW: Int,
    naturalH: Int,
    explicitW: Int?,
    explicitH: Int?,
): ImageDisplayMode {
    if (explicitW != null && explicitH != null && explicitW > 0 && explicitH > 0) {
        return ImageDisplayMode.FitToWidth(aspectRatio = explicitW.toFloat() / explicitH.toFloat())
    }
    if (naturalW <= 0 || naturalH <= 0) return ImageDisplayMode.Wrap
    return ImageDisplayMode.FitToWidth(aspectRatio = naturalW.toFloat() / naturalH.toFloat())
}

/**
 * Renders an [ImageNode]: the decoded image once [loader] resolves it, otherwise
 * the shared labelled placeholder (both while loading and on any failure). The image
 * is fit to the available content width (scaling small images up and large images
 * down, like Anki), preserving the explicit-HTML aspect when the node carries `w`/`h`
 * and otherwise the bitmap's natural aspect. See [imageDisplayMode].
 */
@Composable
fun MediaImage(node: ImageNode, loader: MediaLoader) {
    // Seed from the session cache so an already-decoded image renders on the first frame
    // instead of flashing the placeholder when a reveal (or any recomposition that swaps
    // this composable instance) restarts produceState. Cache miss → null, unchanged
    // loading behaviour. See [MediaLoader.peek].
    val bitmap by produceState<ImageBitmap?>(initialValue = loader.peek(node.src), node.src, loader) {
        value = loader.load(node.src)
    }

    val image = bitmap
    if (image == null) {
        ImageNodePlaceholder(node)
        return
    }

    val modifier = when (
        val mode = imageDisplayMode(
            naturalW = image.width,
            naturalH = image.height,
            explicitW = node.w,
            explicitH = node.h,
        )
    ) {
        is ImageDisplayMode.FitToWidth -> Modifier.fillMaxWidth().aspectRatio(mode.aspectRatio)
        ImageDisplayMode.Wrap -> Modifier.wrapContentSize()
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
