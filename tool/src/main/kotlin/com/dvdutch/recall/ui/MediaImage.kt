package com.dvdutch.recall.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.engine.ImageDims
import com.dvdutch.recall.engine.MediaSyncTracker
import com.dvdutch.recall.engine.imageDims
import com.dvdutch.recall.prefs.RecallStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/** The path prefix every bridge media `src` carries: `/v1/media/<url-encoded-name>`. */
private const val MEDIA_PATH_PREFIX = "/v1/media/"

/** Default session capacity: a session's worth of images, not a durable cache (M2 owns that). */
private const val DEFAULT_MAX_ENTRIES = 16

/** A [MediaLoader] with no media sync to follow: never downloading, nothing arriving. */
private val NOT_DOWNLOADING: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_ARRIVALS: StateFlow<Int> = MutableStateFlow(0)

/** Leads the label of an image whose file is still downloading. */
private const val DOWNLOADING_LABEL = "↓ still downloading"

/**
 * The text on a missing image's placeholder: its filename, so nothing is silently dropped,
 * and while media is still downloading ([downloading]), that it's on its way. Pure and
 * unit-tested.
 */
fun imagePlaceholderLabel(src: String, downloading: Boolean): String =
    if (downloading) "$DOWNLOADING_LABEL — $src" else src

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
 * @param downloads the app's [MediaSyncTracker], when there is one: while media is still
 *   downloading a missing image may yet arrive, so it says so and tries again.
 * @param measure an image's natural size from its bytes; production reads the header
 *   ([imageDims]), else asks [BitmapFactory] — tests substitute a pure function.
 * @param decode injectable byte→bitmap step; production uses [BitmapFactory],
 *   tests substitute a pure function (the real decoder needs the Android runtime).
 */
class MediaLoader(
    private val mediaFileOf: (String) -> File,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    downloads: MediaSyncTracker? = null,
    private val measure: (ByteArray) -> ImageDims? = ::measureImage,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
) {

    /** Convenience: resolve media files from a [RecallStorage]. */
    constructor(
        storage: RecallStorage,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        downloads: MediaSyncTracker? = null,
        measure: (ByteArray) -> ImageDims? = ::measureImage,
        decode: (ByteArray) -> ImageBitmap? = ::decodeImageBitmap,
    ) : this(storage::mediaFile, maxEntries, downloads, measure, decode)

    private val cache = SessionLru<String, ImageBitmap>(maxEntries)

    // Natural pixel size of each image read so far (the decoded bitmap may be downsampled).
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, ImageDims>()

    /** True while media is still downloading ([MediaSyncTracker.active]). */
    val downloading: StateFlow<Boolean> = downloads?.active ?: NOT_DOWNLOADING

    /**
     * Goes up whenever media may have arrived ([MediaSyncTracker.arrivals]): an image that
     * couldn't be loaded is worth loading again. Only successes are cached, so a retry
     * reads the disk afresh.
     */
    val arrivals: StateFlow<Int> = downloads?.arrivals ?: NO_ARRIVALS

    /**
     * Synchronous, disk-free probe of the session cache. Returns the already-decoded
     * bitmap for [src] if a prior [load] cached it, else null — never reads a file or
     * decodes. This lets a composable seed its initial state with a cache hit so an
     * image already in memory renders on the first frame instead of flashing the
     * Loading placeholder (the front→back occlusion reveal swaps composable instances,
     * so the new one's state starts over; without this seed it starts as a null bitmap
     * even for a just-loaded image). A miss returns null and the
     * caller falls back to the normal load path. Mirrors [load]'s filename extraction so
     * a `/v1/media/...` src probes the same key the bare name populated.
     */
    fun peek(src: String): ImageBitmap? {
        val name = mediaFilenameFromSrc(src) ?: return null
        return cache.get(name)
    }

    /**
     * The natural pixel size of [src] as measured from its file by a [load] that succeeded,
     * or null if it hasn't loaded (or couldn't be measured). Unlike the decoded bitmap,
     * never downsampled — what an occlusion's masks are measured in.
     */
    fun naturalSize(src: String): ImageDims? = mediaFilenameFromSrc(src)?.let { sizes[it] }

    suspend fun load(src: String): ImageBitmap? = read(src)?.bitmap

    /**
     * One read of [src] (from the cache unless [fresh]): its bitmap, and whether that may
     * come from a half-written file — one read while media was downloading that was
     * written only moments before. Such a read is never cached, so the next one goes back
     * to the disk; any other is.
     */
    internal suspend fun read(src: String, fresh: Boolean = false): Read? {
        val name = mediaFilenameFromSrc(src) ?: return null
        // Move the file read + decode off the caller's dispatcher: MediaImage's
        // load effect runs on the composition's Main context, and a full-res
        // WebP decode (BitmapFactory) inline there janks frames. Dispatchers.Default
        // makes the loader off-main-safe at the source rather than at each call site.
        return withContext(Dispatchers.Default) {
            if (!fresh) cache.get(name)?.let { return@withContext Read(it, partial = false) }
            val downloadingBefore = downloading.value
            val file = mediaFileOf(name)
            val (bytes, writtenAt) = try {
                if (!file.isFile) return@withContext null // missing media → placeholder
                // The write time after the read: a write still going on shows up as "just now".
                file.readBytes() to file.lastModified()
            } catch (e: Exception) {
                // Cancellation must propagate — swallowing it into a null would mask a
                // cancelled load as a genuine failure (and fight structured concurrency).
                if (e is CancellationException) throw e
                // Any I/O failure → placeholder. Content is preserved as a labelled fallback.
                return@withContext null
            }
            val bitmap = decode(bytes) ?: return@withContext null
            measure(bytes)?.let { sizes[name] = it }
            val downloadingNow = downloadingBefore || downloading.value
            val partial = downloadingNow && System.currentTimeMillis() - writtenAt < SETTLED_MILLIS
            if (!partial) cache.put(name, bitmap)
            Read(bitmap, partial)
        }
    }

    /** A [read]'s bitmap, and whether it may be of a file still being written. */
    internal data class Read(val bitmap: ImageBitmap, val partial: Boolean)
}

/**
 * How long ago a media file must have been written to count as complete while media is
 * still downloading: the engine writes each file in one go, so anything older is whole.
 */
private const val SETTLED_MILLIS = 3_000L

/**
 * Loads [src] for a view, handing each bitmap to [show]: at once if its file is there,
 * else again each time media arrives (one load at a time; arrivals during a load are
 * folded into the next try), calling [onMiss] after each try that finds nothing. A bitmap
 * read while media was still downloading may come from a half-written file, so it is read
 * once more when the download ends. Returns when the image is settled; a file that never
 * arrives keeps it waiting, cancelled with the view.
 */
internal suspend fun MediaLoader.loadImage(src: String, onMiss: () -> Unit = {}, show: (ImageBitmap) -> Unit) {
    var partial = false
    arrivals.first {
        val read = read(src) ?: run {
            onMiss()
            return@first false
        }
        show(read.bitmap)
        partial = read.partial
        true
    }
    if (partial) {
        downloading.first { !it }
        read(src, fresh = true)?.let { show(it.bitmap) }
    }
}

/**
 * Production natural-size probe: the header sniffer ([imageDims]) for the formats it
 * knows, else [BitmapFactory]'s bounds (BMP, HEIF, AVIF…) — never the decoded bitmap's
 * size, which may be downsampled. Android-runtime only.
 */
private fun measureImage(bytes: ByteArray): ImageDims? {
    imageDims(bytes)?.let { return it }
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) ImageDims(bounds.outWidth, bounds.outHeight) else null
    } catch (_: RuntimeException) {
        null // no Android runtime (JVM unit tests): only the header sniffer applies
    }
}

/**
 * Production byte→bitmap decode. Android-runtime only; emulator-verified. Images are
 * always fit to the screen width, so a large photo is decoded downsampled (see
 * [decodeSampleSize]) instead of at full size — a 12 MP photo would otherwise take
 * ~48 MB, enough to run the app out of memory with a few cached. A decode that still
 * runs out of memory falls back to the placeholder.
 */
private fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** Largest dimension a card image is decoded at; ~2× the LP3's 1080 px screen width. */
private const val MAX_DECODE_PX = 2048

/**
 * The power-of-two `inSampleSize` that brings a [width]×[height] image within
 * [maxPx] on both sides (1 for unknown or already-small images). Pure and unit-tested.
 */
fun decodeSampleSize(width: Int, height: Int, maxPx: Int = MAX_DECODE_PX): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / sample > maxPx || height / sample > maxPx) sample *= 2
    return sample
}

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
    data class FitToWidth(val aspectRatio: Float, val stretches: Boolean = false) : ImageDisplayMode

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
        return ImageDisplayMode.FitToWidth(aspectRatio = explicitW.toFloat() / explicitH.toFloat(), stretches = true)
    }
    if (naturalW <= 0 || naturalH <= 0) return ImageDisplayMode.Wrap
    return ImageDisplayMode.FitToWidth(aspectRatio = naturalW.toFloat() / naturalH.toFloat())
}

/**
 * Renders an [ImageNode]: the decoded image once [loader] resolves it, otherwise
 * the shared labelled placeholder (both while loading and on any failure). The image
 * is fit to the available content width (scaling small images up and large images
 * down, like Anki), preserving the explicit-HTML aspect when the node carries `w`/`h`
 * and otherwise the bitmap's natural aspect. See [imageDisplayMode]. An image whose file
 * hasn't been downloaded yet says so, and shows up as soon as it arrives.
 */
@Composable
fun MediaImage(node: ImageNode, loader: MediaLoader) {
    val downloading by loader.downloading.collectAsState()
    // Seed from the session cache so an already-decoded image renders on the first frame
    // instead of flashing the placeholder when a reveal (or any recomposition that swaps
    // this composable instance) starts over. Cache miss → null, unchanged loading
    // behaviour. See [MediaLoader.peek].
    var bitmap by remember(node.src, loader) { mutableStateOf(loader.peek(node.src)) }
    LaunchedEffect(node.src, loader) {
        if (bitmap == null) loader.loadImage(node.src) { bitmap = it }
    }

    val image = bitmap
    if (image == null) {
        ImageNodePlaceholder(node, downloading)
        return
    }

    val mode = imageDisplayMode(
        naturalW = image.width,
        naturalH = image.height,
        explicitW = node.w,
        explicitH = node.h,
    )
    val modifier = when (mode) {
        is ImageDisplayMode.FitToWidth -> Modifier.fillMaxWidth().aspectRatio(mode.aspectRatio)
        ImageDisplayMode.Wrap -> Modifier.wrapContentSize()
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier,
        // An explicit <img width height> aspect STRETCHES the bitmap into it, as a browser
        // does; letterboxing (Fit) would leave dead space either side of a 4:1 box.
        contentScale = if ((mode as? ImageDisplayMode.FitToWidth)?.stretches == true) ContentScale.FillBounds else ContentScale.Fit,
    )
}
