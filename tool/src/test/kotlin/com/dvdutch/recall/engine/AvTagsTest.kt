package com.dvdutch.recall.engine

import anki.card_rendering.AVTag
import anki.card_rendering.TTSTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure unit tests for [soundFilenames] — no backend, just proto [AVTag] inputs.
 * Covers ordering, TTS-skip, and the empty case.
 */
class AvTagsTest {

    private fun sound(name: String): AVTag =
        AVTag.newBuilder().setSoundOrVideo(name).build()

    private fun tts(text: String): AVTag =
        AVTag.newBuilder().setTts(TTSTag.newBuilder().setFieldText(text).build()).build()

    @Test
    fun `empty tag list yields no filenames`() {
        assertEquals(emptyList(), soundFilenames(emptyList()))
    }

    @Test
    fun `sound-or-video tags map to filenames in order`() {
        val tags = listOf(sound("a.mp3"), sound("b.ogg"), sound("c.wav"))
        assertEquals(listOf("a.mp3", "b.ogg", "c.wav"), soundFilenames(tags))
    }

    @Test
    fun `TTS tags are skipped preserving the order of the remaining sound tags`() {
        val tags = listOf(sound("first.mp3"), tts("say this"), sound("second.mp3"))
        assertEquals(listOf("first.mp3", "second.mp3"), soundFilenames(tags))
    }

    @Test
    fun `a list of only TTS tags yields no filenames`() {
        assertEquals(emptyList(), soundFilenames(listOf(tts("one"), tts("two"))))
    }
}
