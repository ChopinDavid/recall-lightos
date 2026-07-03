package com.dvdutch.recall.ui

import com.dvdutch.recall.api.CardPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [activeSideAudio] / [sideHasAudio] — the pure front/back audio
 * selection that both auto-play and the replay affordance key off. Compose-free.
 */
class CardAudioTest {

    private fun card(front: List<String>, back: List<String>) = CardPayload(
        cardId = 1,
        noteId = 1,
        front = emptyList(),
        back = emptyList(),
        states = "",
        nextDueLabels = emptyMap(),
        frontAudio = front,
        backAudio = back,
    )

    @Test
    fun `front side yields the front audio list`() {
        val c = card(front = listOf("q.mp3"), back = listOf("a.mp3"))
        assertEquals(listOf("q.mp3"), activeSideAudio(c, showBack = false))
    }

    @Test
    fun `back side yields the back audio list`() {
        val c = card(front = listOf("q.mp3"), back = listOf("a1.mp3", "a2.mp3"))
        assertEquals(listOf("a1.mp3", "a2.mp3"), activeSideAudio(c, showBack = true))
    }

    @Test
    fun `has-audio is true only when the active side has files`() {
        val frontOnly = card(front = listOf("q.mp3"), back = emptyList())
        assertTrue(sideHasAudio(frontOnly, showBack = false))
        assertFalse(sideHasAudio(frontOnly, showBack = true))
    }

    @Test
    fun `a side with no audio has no replay`() {
        val silent = card(front = emptyList(), back = emptyList())
        assertFalse(sideHasAudio(silent, showBack = false))
        assertFalse(sideHasAudio(silent, showBack = true))
    }
}
