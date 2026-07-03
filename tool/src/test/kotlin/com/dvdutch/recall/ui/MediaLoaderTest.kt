package com.dvdutch.recall.ui

import com.dvdutch.recall.api.BridgeClient
import com.dvdutch.recall.api.BridgeError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the pure, Android-runtime-free parts of media loading:
 * filename extraction (incl. percent-decoding), the session LRU's eviction order,
 * and the failure→null contract of [MediaLoader.load]. Bitmap decoding is
 * [android.graphics.BitmapFactory] and is verified on the emulator instead.
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

    // --- load() failure contract --------------------------------------------

    /** A [BridgeClient] whose transport always fails, so `media()` throws Unreachable. */
    private fun unreachableClient(): BridgeClient =
        BridgeClient(
            baseUrl = "https://bridge.example",
            token = "t",
            engine = MockEngine { throw IOException("connection refused") },
        )

    /** A [BridgeClient] that serves the given bytes for any media request. */
    private fun bytesClient(bytes: ByteArray): BridgeClient =
        BridgeClient(
            baseUrl = "https://bridge.example",
            token = "t",
            engine = MockEngine {
                respond(
                    content = bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("image/webp"),
                        "X-Bridge-Api" to listOf("1"),
                    ),
                )
            },
        )

    @Test
    fun sanityUnreachableClientThrows(): Unit = runBlocking {
        assertFailsWith<BridgeError.Unreachable> { unreachableClient().media("x.png") }
    }

    @Test
    fun loadReturnsNullWhenClientUnreachable(): Unit = runBlocking {
        val loader = MediaLoader(unreachableClient())
        assertNull(loader.load("/v1/media/dog.jpg"))
    }

    @Test
    fun loadReturnsNullWhenSrcHasNoFilename(): Unit = runBlocking {
        val loader = MediaLoader(unreachableClient())
        assertNull(loader.load("not-a-media-url"))
    }

    /**
     * A [BridgeClient] whose `media()` signals [entered] once the request reaches the
     * engine, then parks on [gate] forever — letting a test cancel the loading coroutine
     * while it is suspended mid-fetch.
     */
    private fun suspendingClient(
        entered: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
    ): BridgeClient =
        BridgeClient(
            baseUrl = "https://bridge.example",
            token = "t",
            engine = MockEngine {
                entered.complete(Unit)
                gate.await() // park here until cancelled (the test never completes gate)
                respond(content = byteArrayOf(), status = HttpStatusCode.OK)
            },
        )

    @Test
    fun loadPropagatesCancellation(): Unit = runBlocking {
        // Cancelling a coroutine parked mid-load must surface as CancellationException,
        // not be swallowed into a null (which would mask the cancellation as a load
        // failure and leave the placeholder up as if the fetch had genuinely failed).
        // We capture what load() itself does — return vs. throw — from inside the
        // coroutine, so the assertion reflects load()'s catch behaviour rather than
        // merely the enclosing Job's cancelled state.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val loader = MediaLoader(suspendingClient(entered, gate))
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                loader.load("/v1/media/dog.jpg")
                outcome.complete(null) // load() returned (e.g. swallowed cancellation)
            } catch (e: Throwable) {
                outcome.complete(e) // load() propagated
            }
        }
        entered.await() // producer is now parked inside media()
        job.cancel()
        val thrown = outcome.await()
        assertTrue(thrown is CancellationException, "expected load() to propagate cancellation, got $thrown")
    }

    @Test
    fun loadReturnsNullWhenDecodeFails(): Unit = runBlocking {
        // Non-image bytes: the injected decoder returns null (as BitmapFactory would),
        // so load() yields null rather than throwing. This also proves the client is
        // actually reached and its bytes handed to the decoder.
        var decodeCalls = 0
        val loader = MediaLoader(bytesClient(byteArrayOf(0, 1, 2, 3))) {
            decodeCalls++
            null
        }
        assertNull(loader.load("/v1/media/dog.jpg"))
        assertEquals(1, decodeCalls)
    }
}
