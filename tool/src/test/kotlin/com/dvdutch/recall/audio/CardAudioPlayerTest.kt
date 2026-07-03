package com.dvdutch.recall.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the sequential playback queue / state machine in [CardAudioPlayer].
 *
 * The real playback engine ([RealMediaPlayback], wrapping [android.media.MediaPlayer])
 * needs the Android runtime and is emulator-verified in Task 3. Here a [FakeMediaPlayback]
 * stands in: it records the calls it received and fires its completion callback on demand,
 * so the queue's sequential advance ("start the next when the previous completes") is fully
 * asserted without any Android runtime.
 *
 * [CardAudioPlayer]'s off-main work is driven by an injected synchronous runner so every
 * `play`/`stop`/`release` runs inline on the test thread — deterministic, no sleeps.
 */
class CardAudioPlayerTest {

    /** Runs the submitted work immediately on the calling thread. */
    private val inline: (Runnable) -> Unit = { it.run() }

    /**
     * Records every interaction and exposes [fireCompletion] to simulate a track finishing.
     * A shared [log] across all instances lets tests assert the global start order.
     */
    private class FakeMediaPlayback(private val log: MutableList<String>) : MediaPlayback {
        var source: String? = null
        var prepared = false
        var started = false
        var stopped = false
        var released = false
        private var onCompletion: (() -> Unit)? = null

        override fun setDataSource(path: String) { source = path }
        override fun prepare() { prepared = true }
        override fun start() {
            started = true
            log.add("start:${name(source)}")
        }
        override fun setOnCompletion(cb: () -> Unit) { onCompletion = cb }
        override fun stop() {
            stopped = true
            log.add("stop:${name(source)}")
        }
        override fun release() {
            released = true
            log.add("release:${name(source)}")
        }

        /** The bare filename, for readable log assertions (source is an absolute path). */
        private fun name(path: String?): String? = path?.let { File(it).name }

        /** Simulate the underlying player reaching the end of the current track. */
        fun fireCompletion() {
            onCompletion?.invoke()
        }
    }

    /** A factory that hands out the same pre-built fakes in order, tracking how many were used. */
    private class Factory(private val fakes: List<FakeMediaPlayback>) : () -> MediaPlayback {
        var madeCount = 0
            private set
        override fun invoke(): MediaPlayback = fakes[madeCount++]
    }

    /** resolve() that maps a fixed set of names to existing temp files; everything else → null. */
    private fun resolverFor(vararg names: String): Pair<(String) -> File?, () -> Unit> {
        val root = File.createTempFile("recall-audio", "").apply { delete(); mkdirs() }
        val files = names.associateWith { name ->
            File(root, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1)) }
        }
        val resolve: (String) -> File? = { files[it] }
        val cleanup: () -> Unit = { root.deleteRecursively() }
        return resolve to cleanup
    }

    // --- sequential playback -------------------------------------------------

    @Test
    fun playsListSequentiallyAdvancingOnCompletion() {
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3", "c.mp3")
        val log = mutableListOf<String>()
        val fakes = listOf(FakeMediaPlayback(log), FakeMediaPlayback(log), FakeMediaPlayback(log))
        val factory = Factory(fakes)
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("a.mp3", "b.mp3", "c.mp3"))
        assertEquals(listOf("start:a.mp3"), log, "only the first track starts up front")
        assertEquals(1, factory.madeCount, "the next player isn't built until it's needed")

        fakes[0].fireCompletion()
        assertEquals(listOf("start:a.mp3", "release:a.mp3", "start:b.mp3"), log)

        fakes[1].fireCompletion()
        assertEquals(
            listOf("start:a.mp3", "release:a.mp3", "start:b.mp3", "release:b.mp3", "start:c.mp3"),
            log,
        )

        // Completing the last track releases it and ends the queue — no new player.
        fakes[2].fireCompletion()
        assertTrue(fakes[2].released)
        assertEquals(3, factory.madeCount, "no fourth player is created after the list is done")

        cleanup()
    }

    @Test
    fun eachTrackIsPreparedBeforeStarting() {
        val (resolve, cleanup) = resolverFor("a.mp3")
        val log = mutableListOf<String>()
        val fake = FakeMediaPlayback(log)
        val player = CardAudioPlayer(resolve, Factory(listOf(fake)), inline)

        player.play(listOf("a.mp3"))
        assertTrue(fake.prepared)
        assertTrue(fake.started)
        assertEquals("a.mp3", File(fake.source!!).name)
        cleanup()
    }

    // --- skip missing --------------------------------------------------------

    @Test
    fun skipsFilesThatResolveToNull() {
        // "gap.mp3" resolves to null → it must be skipped and "b.mp3" played next.
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3")
        val log = mutableListOf<String>()
        val fakes = listOf(FakeMediaPlayback(log), FakeMediaPlayback(log))
        val factory = Factory(fakes)
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("a.mp3", "gap.mp3", "b.mp3"))
        assertEquals(listOf("start:a.mp3"), log)

        fakes[0].fireCompletion()
        // The gap is skipped without building a player for it: only 2 players ever made.
        assertEquals(listOf("start:a.mp3", "release:a.mp3", "start:b.mp3"), log)
        assertEquals(2, factory.madeCount, "no player is built for the unresolved entry")
        cleanup()
    }

    @Test
    fun skipsFilesThatResolveToAMissingFile() {
        // resolve returns a File that doesn't exist on disk → treated as missing → skipped.
        val root = File.createTempFile("recall-audio", "").apply { delete(); mkdirs() }
        val real = File(root, "a.mp3").apply { writeBytes(ByteArray(1)) }
        val ghost = File(root, "ghost.mp3") // never created
        val resolve: (String) -> File? = { name ->
            when (name) {
                "a.mp3" -> real
                "ghost.mp3" -> ghost
                else -> null
            }
        }
        val log = mutableListOf<String>()
        val fakes = listOf(FakeMediaPlayback(log))
        val factory = Factory(fakes)
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("ghost.mp3", "a.mp3"))
        assertEquals(listOf("start:a.mp3"), log)
        assertEquals(1, factory.madeCount)
        root.deleteRecursively()
    }

    @Test
    fun emptyListIsNoOp() {
        val (resolve, cleanup) = resolverFor("a.mp3")
        val log = mutableListOf<String>()
        val factory = Factory(emptyList())
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(emptyList())
        assertEquals(emptyList(), log)
        assertEquals(0, factory.madeCount)
        cleanup()
    }

    @Test
    fun allMissingListIsNoOp() {
        val resolve: (String) -> File? = { null }
        val log = mutableListOf<String>()
        val factory = Factory(emptyList())
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("x.mp3", "y.mp3"))
        assertEquals(emptyList(), log)
        assertEquals(0, factory.madeCount, "no player is ever built when nothing resolves")
    }

    // --- restart mid-playback (double-grade race) ----------------------------

    @Test
    fun playAgainMidPlaybackStopsAndReleasesTheOldPlayerBeforeStartingTheNew() {
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3")
        val log = mutableListOf<String>()
        val old = FakeMediaPlayback(log)
        val new = FakeMediaPlayback(log)
        val factory = Factory(listOf(old, new))
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("a.mp3"))
        assertEquals(listOf("start:a.mp3"), log)

        player.play(listOf("b.mp3"))
        // Old must be stopped AND released before the new one starts.
        assertEquals(listOf("start:a.mp3", "stop:a.mp3", "release:a.mp3", "start:b.mp3"), log)
        assertTrue(old.stopped && old.released)
        cleanup()
    }

    @Test
    fun aLateCompletionFromAReplacedPlayerDoesNotAdvanceTheNewQueue() {
        // The double-grade lesson: a stale completion callback from the player we just
        // replaced must be inert — it must not start anything in the new queue.
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3", "c.mp3")
        val log = mutableListOf<String>()
        val old = FakeMediaPlayback(log)
        val new = FakeMediaPlayback(log)
        val factory = Factory(listOf(old, new))
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("a.mp3", "c.mp3")) // old queue would advance a→c
        player.play(listOf("b.mp3"))          // replace with a fresh queue
        val before = log.toList()

        old.fireCompletion() // stale callback fires late
        assertEquals(before, log, "a stale completion must not touch the current queue")
        cleanup()
    }

    // --- stop / release ------------------------------------------------------

    @Test
    fun stopStopsAndReleasesTheCurrentPlayerAndEndsTheQueue() {
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3")
        val log = mutableListOf<String>()
        val a = FakeMediaPlayback(log)
        val factory = Factory(listOf(a))
        val player = CardAudioPlayer(resolve, factory, inline)

        player.play(listOf("a.mp3", "b.mp3"))
        player.stop()
        assertTrue(a.stopped && a.released)

        // A late completion from the stopped player must not resume the queue.
        val before = log.toList()
        a.fireCompletion()
        assertEquals(before, log)
        assertEquals(1, factory.madeCount, "the queued b.mp3 never starts after stop()")
        cleanup()
    }

    @Test
    fun stopIsIdempotent() {
        val (resolve, cleanup) = resolverFor("a.mp3")
        val log = mutableListOf<String>()
        val a = FakeMediaPlayback(log)
        val player = CardAudioPlayer(resolve, Factory(listOf(a)), inline)

        player.play(listOf("a.mp3"))
        player.stop()
        player.stop() // no double stop/release, no crash
        assertEquals(1, log.count { it == "stop:a.mp3" })
        assertEquals(1, log.count { it == "release:a.mp3" })
        cleanup()
    }

    @Test
    fun stopWithNothingPlayingIsNoOp() {
        val (resolve, cleanup) = resolverFor("a.mp3")
        val player = CardAudioPlayer(resolve, Factory(emptyList()), inline)
        player.stop() // must not crash
        cleanup()
    }

    @Test
    fun releaseCleansUpCurrentPlayerAndNoFurtherCallbacksAct() {
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3")
        val log = mutableListOf<String>()
        val a = FakeMediaPlayback(log)
        val player = CardAudioPlayer(resolve, Factory(listOf(a)), inline)

        player.play(listOf("a.mp3", "b.mp3"))
        player.release()
        assertTrue(a.stopped && a.released)

        val before = log.toList()
        a.fireCompletion()
        assertEquals(before, log, "no callback acts after release()")
        cleanup()
    }

    @Test
    fun releaseIsIdempotent() {
        val (resolve, cleanup) = resolverFor("a.mp3")
        val log = mutableListOf<String>()
        val a = FakeMediaPlayback(log)
        val player = CardAudioPlayer(resolve, Factory(listOf(a)), inline)

        player.play(listOf("a.mp3"))
        player.release()
        player.release()
        assertEquals(1, log.count { it == "release:a.mp3" })
        cleanup()
    }

    @Test
    fun playAfterReleaseStartsFresh() {
        // release() cleans up but the object stays usable for a subsequent play().
        val (resolve, cleanup) = resolverFor("a.mp3", "b.mp3")
        val log = mutableListOf<String>()
        val a = FakeMediaPlayback(log)
        val b = FakeMediaPlayback(log)
        val player = CardAudioPlayer(resolve, Factory(listOf(a, b)), inline)

        player.play(listOf("a.mp3"))
        player.release()
        player.play(listOf("b.mp3"))
        assertTrue(b.started)
        assertFalse(a.started == b.started && a === b)
        cleanup()
    }
}
