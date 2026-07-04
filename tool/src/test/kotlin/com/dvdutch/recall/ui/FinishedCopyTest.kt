package com.dvdutch.recall.ui

import com.dvdutch.recall.api.Counts
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [FinishedCopy] — the pure done-screen copy shown when a study
 * session reaches [com.dvdutch.recall.study.StudyState.Finished]. The session
 * serves cards until the engine's queue is truly exhausted (AnkiDroid's model),
 * so the done screen only ever appears at real exhaustion; its copy must be
 * TRUTHFUL about what, if anything, comes back later today.
 *
 * The mapping lives in a plain object so it is testable on the JVM with no
 * Compose/Android runtime.
 */
class FinishedCopyTest {

    private fun counts(new: Int = 0, learning: Int = 0, review: Int = 0) =
        Counts(new = new, learning = learning, review = review)

    // The reviewed-count line is always present, whatever the counts.
    @Test
    fun `always reports the reviewed count`() {
        assertEquals("session done — 7 reviewed", FinishedCopy.lines(7, counts()).first())
    }

    // Truly nothing left: a plain congrats, no "due later" line.
    @Test
    fun `no cards remaining is a plain congrats`() {
        assertEquals(
            listOf("session done — 3 reviewed", "all caught up"),
            FinishedCopy.lines(3, counts()),
        )
    }

    // Learning cards remain (the queue emptied because they are scheduled a few
    // minutes out): say they will be due again later today, pluralized on count.
    @Test
    fun `learning cards due later today are announced`() {
        assertEquals(
            listOf("session done — 5 reviewed", "3 cards will be due again later today"),
            FinishedCopy.lines(5, counts(learning = 3)),
        )
    }

    // A single learning card uses the singular.
    @Test
    fun `a single learning card is singular`() {
        assertEquals(
            "1 card will be due again later today",
            FinishedCopy.lines(1, counts(learning = 1))[1],
        )
    }

    // Null counts (no report available) still gives a coherent congrats.
    @Test
    fun `null counts falls back to plain congrats`() {
        assertEquals(
            listOf("session done — 0 reviewed", "all caught up"),
            FinishedCopy.lines(0, null),
        )
    }
}
