package com.dvdutch.recall.engine

import anki.card_rendering.RenderedTemplateNode
import anki.card_rendering.RenderedTemplateReplacement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [assembleCardSide], the reimplementation of AnkiDroid's
 * `TemplateManager.applyCustomFilters`.
 *
 * The load-bearing case is `{{FrontSide}}` injection on the answer side: rslib does
 * NOT resolve `{{FrontSide}}` — it emits a replacement node with an EMPTY
 * `currentText`, so the client must inject the rendered question HTML. A regression
 * here silently drops the entire front line from the revealed answer (the on-device
 * bug: user saw `<hr>` + back only). The parity harness cannot catch this because it
 * tests the HTML→node compiler, not this template-node→HTML assembly step.
 */
class CardHtmlTest {

    private fun text(s: String): RenderedTemplateNode =
        RenderedTemplateNode.newBuilder().setText(s).build()

    private fun replacement(fieldName: String, currentText: String): RenderedTemplateNode =
        RenderedTemplateNode.newBuilder()
            .setReplacement(
                RenderedTemplateReplacement.newBuilder()
                    .setFieldName(fieldName)
                    .setCurrentText(currentText)
                    .build(),
            )
            .build()

    @Test
    fun `text nodes are appended verbatim`() {
        val nodes = listOf(text("hello "), text("world"))
        assertEquals("hello world", assembleCardSide(nodes))
    }

    @Test
    fun `resolved replacement uses currentText`() {
        val nodes = listOf(text("A: "), replacement("Back", "the answer"))
        assertEquals("A: the answer", assembleCardSide(nodes))
    }

    @Test
    fun `answer FrontSide replacement is injected with the rendered front`() {
        // Shape of the reproduced card's answer: `{{FrontSide}}<hr id=answer>{{Back}}`
        // where rslib leaves FrontSide's currentText EMPTY.
        val answerNodes = listOf(
            replacement("FrontSide", ""),
            text("<hr id=answer>"),
            replacement("Back", "the back"),
        )
        val front = "Aspect signal words: foo"
        val assembled = assembleCardSide(answerNodes, frontSide = front)
        assertEquals("Aspect signal words: foo<hr id=answer>the back", assembled)
    }

    @Test
    fun `null frontSide leaves FrontSide replacement empty (question side)`() {
        // The question side never carries {{FrontSide}}; passing null must not crash
        // and must fall through to currentText (empty here), matching the reference's
        // frontSide=null question call.
        val nodes = listOf(replacement("FrontSide", ""), text("x"))
        assertEquals("x", assembleCardSide(nodes, frontSide = null))
    }
}
