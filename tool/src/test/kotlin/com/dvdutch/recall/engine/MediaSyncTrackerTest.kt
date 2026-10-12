package com.dvdutch.recall.engine

import com.dvdutch.recall.ui.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [MediaSyncTracker] follows a background media sync until it ends; [heldFor] keeps brief ones off screen. */
class MediaSyncTrackerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `watch follows a running sync until it ends, counting an arrival per check`() {
        val statuses = ArrayDeque(listOf(true, true, false))
        val checks = AtomicInteger()
        val tracker = MediaSyncTracker(
            isActive = { checks.incrementAndGet(); synchronized(statuses) { statuses.removeFirst() } },
            scope = scope,
            pollMillis = 10,
        )
        assertFalse(tracker.active.value)

        tracker.watch()

        // Running from the moment it's started, before the engine is even asked.
        assertTrue(tracker.active.value)
        waitFor(message = { "watch ended" }) { !tracker.active.value && checks.get() == 3 }
        assertEquals(3, tracker.arrivals.value, "an arrival per check, the last one at the end")
        Thread.sleep(50)
        assertEquals(3, checks.get(), "no more checks once the sync is done")
    }

    @Test
    fun `a second watch while one runs doesn't start another, and a later one does`() {
        val running = AtomicBoolean(true)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val tracker = MediaSyncTracker(
            isActive = {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                delay(5)
                inFlight.decrementAndGet()
                running.get()
            },
            scope = scope,
            pollMillis = 5,
        )

        repeat(3) { tracker.watch() }
        Thread.sleep(60)
        running.set(false)
        waitFor(message = { "watch ended" }) { !tracker.active.value }
        assertEquals(1, maxInFlight.get(), "one poll loop at a time")

        // The next media sync is followed afresh.
        running.set(true)
        tracker.watch()
        assertTrue(tracker.active.value)
        running.set(false)
        waitFor(message = { "second watch ended" }) { !tracker.active.value }
    }

    @Test
    fun `a status read that fails ends the watch`() {
        val tracker = MediaSyncTracker(
            isActive = { throw IllegalStateException("collection closed") },
            scope = scope,
            pollMillis = 10,
        )

        tracker.watch()

        waitFor(message = { "watch ended" }) { !tracker.active.value }
        assertEquals(1, tracker.arrivals.value)
    }

    @Test
    fun `heldFor only reports a state that lasts`() = runBlocking {
        val source = MutableStateFlow(false)
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val job = launch(Dispatchers.Default) { source.heldFor(150).collect { seen.add(it) } }
        waitFor(message = { "initial false" }) { seen == listOf(false) }

        // A blip shorter than the hold never shows.
        source.value = true
        delay(40)
        source.value = false
        delay(250)
        assertTrue(seen.none { it }, "a short blip must not show, saw $seen")

        // One that lasts does, and clears at once when it ends.
        source.value = true
        waitFor(message = { "held true" }) { seen.lastOrNull() == true }
        source.value = false
        waitFor(message = { "back to false" }) { seen.lastOrNull() == false }
        job.cancel()
    }

    @Test
    fun `a sync started just as the last one ends is still followed`() {
        val running = AtomicBoolean(true)
        val checks = AtomicInteger()
        lateinit var tracker: MediaSyncTracker
        tracker = MediaSyncTracker(
            isActive = {
                // The first check sees the old sync done — just as a new one is started.
                if (checks.incrementAndGet() == 1) {
                    tracker.watch()
                    false
                } else {
                    running.get()
                }
            },
            scope = scope,
            pollMillis = 10,
        )

        tracker.watch()

        waitFor(message = { "kept checking" }) { checks.get() >= 3 }
        assertTrue(tracker.active.value, "the new sync is being followed")
        running.set(false)
        waitFor(message = { "watch ended" }) { !tracker.active.value }
    }
}
