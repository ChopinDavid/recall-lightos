package com.dvdutch.recall.ui

import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.UnsupportedNode
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

    @Test
    fun `a track index resolves to its own distinct filename`() {
        val list = listOf("word.mp3", "sentence.mp3")
        assertEquals(listOf("word.mp3"), trackFilenames(list, 0))
        assertEquals(listOf("sentence.mp3"), trackFilenames(list, 1))
    }

    @Test
    fun `an out-of-range track resolves to nothing playable`() {
        val list = listOf("only.mp3")
        assertEquals(emptyList(), trackFilenames(list, 5))
        assertEquals(emptyList(), trackFilenames(emptyList(), 0))
    }

    private fun cardNodes(front: List<RenderNode>, back: List<RenderNode>) = CardPayload(
        cardId = 1, noteId = 1, front = front, back = back, states = "", nextDueLabels = emptyMap(),
    )

    @Test
    fun `an inline-only audio side needs no aggregate replay row`() {
        // Audio positioned as an inline run — no aggregate UnsupportedNode("audio").
        val front = listOf(TextNode(listOf(TextRun("он "), TextRun("", audioTrack = 0))))
        val c = cardNodes(front = front, back = emptyList())
        assertFalse(sideNeedsAggregateReplay(c, showBack = false))
    }

    @Test
    fun `a positionless side keeps the aggregate replay row`() {
        // The engine's defensive aggregate marker → the bottom row is offered.
        val back = listOf<RenderNode>(TextNode(listOf(TextRun("text"))), UnsupportedNode("audio"))
        val c = cardNodes(front = emptyList(), back = back)
        assertTrue(sideNeedsAggregateReplay(c, showBack = true))
    }
}
