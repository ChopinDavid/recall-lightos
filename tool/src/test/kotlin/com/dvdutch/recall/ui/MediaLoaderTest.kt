package com.dvdutch.recall.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.dvdutch.recall.prefs.RecallStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.dvdutch.recall.engine.MediaSyncTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

/** Minimal [ImageBitmap] stub for cache-identity assertions (no Android runtime). */
private val FakeBitmap: ImageBitmap = object : ImageBitmap {
    override val width: Int = 1
    override val height: Int = 1
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = false
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
    override fun prepareToDraw() = Unit
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit
}

/**
 * Unit coverage for the pure, Android-runtime-free parts of media loading:
 * filename extraction (incl. percent-decoding), the session LRU's eviction order,
 * and the file-read + failure→null contract of [MediaLoader.load]. Bitmap decoding
 * is [android.graphics.BitmapFactory] and is verified on the emulator instead — the
 * tests inject a pure decoder that echoes the byte count so file reads are asserted
 * without an Android runtime.
 */
/** A PNG header for a 64×32 image (enough for the size probe; never decoded here). */
private val PNG_64x32: ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
    0, 0, 0, 64, 0, 0, 0, 32, 8, 6, 0, 0, 0,
)

class MediaLoaderTest {

    // --- filename extraction -------------------------------------------------

    @Test
    fun extractsPlainFilename() {
        assertEquals("dog.jpg", mediaFilenameFromSrc("/v1/media/dog.jpg"))
    }

    @Test
    fun percentDecodesSpaces() {
        assertEquals("map europe.webp", mediaFilenameFromSrc("/v1/media/map%20europe.webp"))
    }

    @Test
    fun percentDecodesNonAscii() {
        // A UTF-8 percent-encoded Cyrillic name round-trips to the decoded form.
        assertEquals("кот.png", mediaFilenameFromSrc("/v1/media/%D0%BA%D0%BE%D1%82.png"))
    }

    @Test
    fun nullForSrcMissingPrefix() {
        assertNull(mediaFilenameFromSrc("https://example.com/dog.jpg"))
        assertNull(mediaFilenameFromSrc("/v1/media/"))
    }

    // --- bare-filename contract (Kotlin render-compiler port) ---------------

    @Test
    fun bareFilenameIsReturnedAsIs() {
        // The compiler now emits ImageNode.src as the bare, percent-decoded name.
        assertEquals("dog.jpg", mediaFilenameFromSrc("dog.jpg"))
        assertEquals("map europe.webp", mediaFilenameFromSrc("map europe.webp"))
        assertEquals("кот.png", mediaFilenameFromSrc("кот.png"))
    }

    @Test
    fun bareEmptyOrAbsoluteSrcIsRejected() {
        assertNull(mediaFilenameFromSrc(""))
        assertNull(mediaFilenameFromSrc("https://example.com/dog.jpg"))
        assertNull(mediaFilenameFromSrc("/some/abs/path.png"))
    }

    // --- LRU eviction --------------------------------------------------------

    @Test
    fun lruEvictsLeastRecentlyUsedOnCapacity() {
        val lru = SessionLru<String, Int>(maxEntries = 2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("c", 3) // evicts "a" (LRU)

        assertNull(lru.get("a"))
        assertEquals(2, lru.get("b"))
        assertEquals(3, lru.get("c"))
    }

    @Test
    fun lruGetRefreshesRecency() {
        val lru = SessionLru<String, Int>(maxEntries = 2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.get("a")     // "a" now most-recently-used
        lru.put("c", 3)  // evicts "b" instead of "a"

        assertEquals(1, lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals(3, lru.get("c"))
    }

    @Test
    fun lruReinsertUpdatesValueAndRecency() {
        val lru = SessionLru<String, Int>(maxEntries = 2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("a", 11) // update + refresh "a"
        lru.put("c", 3)  // evicts "b"

        assertEquals(11, lru.get("a"))
        assertNull(lru.get("b"))
    }

    // --- load() file-read contract ------------------------------------------

    private fun tmpMediaDir(): RecallStorage {
        val root = File.createTempFile("recall-media", "").apply { delete(); mkdirs() }
        val storage = RecallStorage(root)
        storage.mediaDir.mkdirs()
        return storage
    }

    @Test
    fun loadReadsBytesFromTheMediaFileAndHandsThemToDecode(): Unit = runBlocking {
        // The real decoder (BitmapFactory) needs the Android runtime, so the injected
        // decoder records the byte count it received: seeing 42 bytes proves load()
        // read the on-disk file and handed its contents to decode.
        val storage = tmpMediaDir()
        storage.mediaFile("dog.jpg").writeBytes(ByteArray(42))
        var seen = -1
        val loader = MediaLoader(storage) { bytes -> seen = bytes.size; null }
        loader.load("dog.jpg")
        assertEquals(42, seen)
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun loadReturnsNullWhenFileMissing(): Unit = runBlocking {
        val storage = tmpMediaDir()
        var decodeCalls = 0
        val loader = MediaLoader(storage) { decodeCalls++; null }
        assertNull(loader.load("absent.jpg"))
        assertEquals(0, decodeCalls, "a missing file must never reach the decoder")
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun loadReturnsNullWhenSrcHasNoFilename(): Unit = runBlocking {
        val storage = tmpMediaDir()
        var decodeCalls = 0
        val loader = MediaLoader(storage) { decodeCalls++; null }
        assertNull(loader.load("not-a-media-url"))
        assertNull(loader.load(""))
        assertEquals(0, decodeCalls, "a non-media src never reaches the file/decoder")
        storage.collectionDir.deleteRecursively()
    }

    // --- peek() synchronous cache probe -------------------------------------

    @Test
    fun peekReturnsNullBeforeAnyLoad(): Unit = runBlocking {
        // A filename never loaded is not in the session cache: peek is a pure probe
        // that never touches disk, so it must report a miss (null) synchronously.
        val storage = tmpMediaDir()
        storage.mediaFile("dog.jpg").writeBytes(ByteArray(4))
        var decodeCalls = 0
        val loader = MediaLoader(storage) { bytes -> decodeCalls++; FakeBitmap }
        assertNull(loader.peek("dog.jpg"))
        assertEquals(0, decodeCalls, "peek must never read or decode a file")
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun peekReturnsCachedBitmapAfterLoad(): Unit = runBlocking {
        // Once load() has cached a bitmap for a name, peek returns that same bitmap
        // synchronously — this is the front→back reveal seed that avoids the flash.
        val storage = tmpMediaDir()
        storage.mediaFile("dog.jpg").writeBytes(ByteArray(4))
        val loader = MediaLoader(storage) { FakeBitmap }
        assertNull(loader.peek("dog.jpg"))
        loader.load("dog.jpg")
        assertEquals(FakeBitmap, loader.peek("dog.jpg"))
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun peekResolvesFilenameSameWayAsLoad(): Unit = runBlocking {
        // load() caches by the extracted bare filename; peek must apply the same
        // extraction so a `/v1/media/...` src hits the cache the bare name populated.
        val storage = tmpMediaDir()
        storage.mediaFile("dog.jpg").writeBytes(ByteArray(4))
        val loader = MediaLoader(storage) { FakeBitmap }
        loader.load("dog.jpg")
        assertEquals(FakeBitmap, loader.peek("/v1/media/dog.jpg"))
    }

    @Test
    fun peekReturnsNullForNonMediaSrc(): Unit = runBlocking {
        val storage = tmpMediaDir()
        val loader = MediaLoader(storage) { FakeBitmap }
        assertNull(loader.peek(""))
        assertNull(loader.peek("https://example.com/dog.jpg"))
    }

    @Test
    fun loadReturnsNullWhenDecodeFails(): Unit = runBlocking {
        // Undecodable bytes: the injected decoder returns null (as BitmapFactory would),
        // so load() yields null rather than throwing. Also proves the file is read and
        // its bytes handed to the decoder.
        val storage = tmpMediaDir()
        storage.mediaFile("dog.jpg").writeBytes(byteArrayOf(0, 1, 2, 3))
        var decodeCalls = 0
        val loader = MediaLoader(storage) { decodeCalls++; null }
        assertNull(loader.load("dog.jpg"))
        assertEquals(1, decodeCalls)
        storage.collectionDir.deleteRecursively()
    }

    // --- media still downloading --------------------------------------------

    @Test
    fun aMissingImageLoadsOnceItsFileArrives(): Unit = runBlocking {
        // A miss is never cached, so the retry after media arrives reads the disk afresh.
        val storage = tmpMediaDir()
        val loader = MediaLoader(storage) { FakeBitmap }
        assertNull(loader.load("cell.png"))
        storage.mediaFile("cell.png").writeBytes(ByteArray(4))
        assertEquals(FakeBitmap, loader.load("cell.png"))
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun aLoaderFollowsTheMediaTrackerItIsGiven() {
        val idle = MediaLoader(tmpMediaDir())
        assertFalse(idle.downloading.value, "no tracker: never downloading")
        assertEquals(0, idle.arrivals.value)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = AtomicBoolean(true)
        val tracker = MediaSyncTracker(isActive = { running.get() }, scope = scope, pollMillis = 10)
        val loader = MediaLoader(tmpMediaDir(), downloads = tracker)
        tracker.watch()
        assertTrue(loader.downloading.value)
        waitFor(message = { "an arrival" }) { loader.arrivals.value > 0 }
        running.set(false)
        waitFor(message = { "done" }) { !loader.downloading.value }
        scope.cancel()
    }

    @Test
    fun aPlaceholderSaysWhenItsImageIsStillDownloading() {
        assertEquals("cell.png", imagePlaceholderLabel("cell.png", downloading = false))
        assertEquals("↓ still downloading — cell.png", imagePlaceholderLabel("cell.png", downloading = true))
    }

    @Test
    fun aLoadRecordsTheImagesNaturalSize(): Unit = runBlocking {
        val storage = tmpMediaDir()
        storage.mediaFile("cell.png").writeBytes(PNG_64x32)
        val loader = MediaLoader(storage) { FakeBitmap }
        assertNull(loader.naturalSize("cell.png"), "nothing known before a load")
        loader.load("cell.png")
        assertEquals(com.dvdutch.recall.engine.ImageDims(64, 32), loader.naturalSize("cell.png"))
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun anImageReadWhileMediaDownloadsIsReadAgainOnceItsDone() {
        val storage = tmpMediaDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = AtomicBoolean(true)
        val tracker = MediaSyncTracker(isActive = { running.get() }, scope = scope, pollMillis = 10)
        var decodes = 0
        val loader = MediaLoader(storage, downloads = tracker) { decodes++; FakeBitmap }
        tracker.watch()

        val shown = java.util.Collections.synchronizedList(mutableListOf<ImageBitmap>())
        val misses = java.util.concurrent.atomic.AtomicInteger()
        val job = scope.launch { loader.loadImage("cell.png", onMiss = { misses.incrementAndGet() }) { shown.add(it) } }

        // Not here yet: each try misses, and keeps waiting.
        waitFor(message = { "a miss" }) { misses.get() >= 1 }
        assertTrue(shown.isEmpty())

        // It arrives mid-download: shown at once, but not cached (it may be half-written).
        storage.mediaFile("cell.png").writeBytes(ByteArray(4))
        waitFor(message = { "shown" }) { shown.size == 1 }
        assertNull(loader.peek("cell.png"))

        // The download ends: read once more, now cached, and the load settles.
        running.set(false)
        waitFor(message = { "read again" }) { shown.size == 2 }
        waitFor(message = { "settled" }) { job.isCompleted }
        assertEquals(FakeBitmap, loader.peek("cell.png"))
        scope.cancel()
        storage.collectionDir.deleteRecursively()
    }

    @Test
    fun anImageAlreadyOnDiskIsCachedEvenDuringADownload(): Unit = runBlocking {
        // Written well before this read: complete, so a reveal can draw it from the cache.
        val storage = tmpMediaDir()
        storage.mediaFile("old.png").apply { writeBytes(ByteArray(4)); setLastModified(System.currentTimeMillis() - 60_000) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = MediaSyncTracker(isActive = { true }, scope = scope, pollMillis = 10)
        val loader = MediaLoader(storage, downloads = tracker) { FakeBitmap }
        tracker.watch()

        val read = loader.read("old.png")!!
        assertFalse(read.partial)
        assertEquals(FakeBitmap, loader.peek("old.png"))
        scope.cancel()
        storage.collectionDir.deleteRecursively()
    }
}
