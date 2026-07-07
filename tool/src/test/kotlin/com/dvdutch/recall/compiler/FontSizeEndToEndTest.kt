package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1 end-to-end: the Russian Core 5000 size hierarchy reproduced as per-node
 * scales. Corners (`--font-size-tiny` 10px) shrink hard; the sentence
 * (`--font-size-medium` 18px) and translation/back (`--font-size-small` 14px) land in
 * proportion to the 28px card base; the word (unsized, inherits regular) stays 1.
 */
class FontSizeEndToEndTest {

    private val css = """
        :root {
          --font-size-regular: 28px;
          --font-size-medium: 18px;
          --font-size-small: 14px;
          --font-size-tiny: 10px;
        }
        .prettify-flashcard { font-size: var(--font-size-regular); }
        .header-container { display: flex; justify-content: space-between; }
        .center-text { flex: 1; text-align: center; }
        .left-text { flex: .25; text-align: left; font-size: var(--font-size-tiny); }
        .right-text { flex: .25; text-align: right; font-size: var(--font-size-tiny); }
        .prettify-field--back { font-size: var(--font-size-small); }
        .prettify-field--sentence { font-size: var(--font-size-medium); }
        .prettify-field--sentence-translation { font-size: var(--font-size-small); }
    """.trimIndent()

    private val html = """
        <div class="prettify-flashcard">
          <div class="header-container">
            <div class="left-text">8</div>
            <div class="center-text">
              <div class="prettify-field prettify-field--front">он</div>
              <hr class="prettify-divider prettify-divider--answer" />
              <div class="prettify-field prettify-field--back">he</div>
            </div>
            <div class="right-text">D 100</div>
          </div>
          <hr class="prettify-divider prettify-divider--answer" />
          <div class="prettify-field prettify-field--sentence">Я мой друг.</div>
          <div class="prettify-field prettify-field--sentence-translation">my friend.</div>
        </div>
    """.trimIndent()

    private fun textScale(nodes: List<TextNode>, contains: String): Float =
        nodes.first { n -> n.runs.joinToString("") { it.s }.contains(contains) }.scale

    @Test
    fun hierarchyScalesMatchDeck() {
        val nodes = compileHtml(html, side = "back", css = css)
        val top = nodes.filterIsInstance<TextNode>()
        val row = nodes.first { it is RowNode } as RowNode
        val cornerScale = (row.cells[0].nodes.first() as TextNode).scale // "8", tiny
        val wordScale = (row.cells[1].nodes.first() as TextNode).scale // "он", regular/unsized
        val backScale = (row.cells[1].nodes.last() as TextNode).scale // "he", small
        val dScale = (row.cells[2].nodes.first() as TextNode).scale // "D 100", tiny

        // Corners: 10/28 ≈ 0.357 (just above the 0.35 clamp floor).
        assertEquals(10f / 28f, cornerScale, 1e-4f)
        assertEquals(10f / 28f, dScale, 1e-4f)
        // Word inherits regular -> no scaling.
        assertEquals(1f, wordScale, 1e-4f)
        // Back/translation: 14/28 = 0.5.
        assertEquals(0.5f, backScale, 1e-4f)
        // Sentence: 18/28 ≈ 0.643; translation 0.5. Hierarchy sentence > translation.
        val sentence = textScale(top, "друг")
        val translation = textScale(top, "friend")
        assertEquals(18f / 28f, sentence, 1e-4f)
        assertEquals(14f / 28f, translation, 1e-4f)
        assertTrue(sentence > translation, "sentence must be larger than its translation")
        assertTrue(wordScale > sentence, "word must be larger than the sentence")
        assertTrue(sentence > cornerScale, "sentence must be larger than the tiny corners")
    }

    @Test
    fun decksWithoutFontSizeAreUnscaled() {
        // Regression guard: Periodic/Type/Occlusion decks mostly lack font-size CSS, so
        // every node must stay scale 1 (no shrink/blow-up).
        val plainCss = ".card { color: black; }"
        val plainHtml = "<div>Hydrogen</div><div>H</div>"
        val nodes = compileHtml(plainHtml, side = "back", css = plainCss)
        for (n in nodes.filterIsInstance<TextNode>()) {
            assertEquals(1f, n.scale, 1e-4f, "unsized deck node must not scale")
        }
    }
}
