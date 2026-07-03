package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [gradeButtons] — the pure fixed-order + label-extraction that
 * turns a card's `next_due_labels` map into the four grade buttons. Compose-free.
 */
class GradeButtonsTest {

    private val labels = mapOf(
        "again" to "<⁨1⁩m",
        "hard" to "<⁨6⁩m",
        "good" to "<⁨10⁩m",
        "easy" to "⁨3⁩d",
    )

    @Test
    fun `buttons come out in again hard good easy order`() {
        val ratings = gradeButtons(labels).map { it.rating }
        assertEquals(listOf("again", "hard", "good", "easy"), ratings)
    }

    @Test
    fun `each button carries its fixed word`() {
        val words = gradeButtons(labels).map { it.word }
        assertEquals(listOf("Again", "Hard", "Good", "Easy"), words)
    }

    @Test
    fun `interval labels are taken verbatim including bidi-isolate chars`() {
        val buttons = gradeButtons(labels).associateBy { it.rating }
        assertEquals("<⁨1⁩m", buttons.getValue("again").interval)
        assertEquals("⁨3⁩d", buttons.getValue("easy").interval)
    }

    @Test
    fun `a missing rating falls back to an empty interval but still renders`() {
        val buttons = gradeButtons(mapOf("again" to "1m", "good" to "10m"))
        assertEquals(4, buttons.size)
        assertEquals("", buttons.first { it.rating == "hard" }.interval)
        assertEquals("", buttons.first { it.rating == "easy" }.interval)
    }
}
