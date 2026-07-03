package com.dvdutch.recall.ui

import com.dvdutch.recall.prefs.RecallStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the pure, Android-runtime-free parts of media loading:
 * filename extraction (incl. percent-decoding), the session LRU's eviction order,
 * and the file-read + failure→null contract of [MediaLoader.load]. Bitmap decoding
 * is [android.graphics.BitmapFactory] and is verified on the emulator instead — the
 * tests inject a pure decoder that echoes the byte count so file reads are asserted
 * without an Android runtime.
 */
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
}
