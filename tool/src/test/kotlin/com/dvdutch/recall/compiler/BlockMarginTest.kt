package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fix D end-to-end: per-class vertical margins from the notetype CSS flow onto the
 * compiled block nodes' [TextNode.marginTop]/[TextNode.marginBottom] (em) so the
 * UI can reproduce the deck's vertical rhythm. The `<hr>` divider carries its own
 * margins too ([RuleNode.marginTop]/[RuleNode.marginBottom]).
 */
class BlockMarginTest {

    @Test
    fun tagsBlockCarriesTopAndBottomMargin() {
        val css = ".prettify-tags { margin-top: 2em; margin-bottom: 1em }"
        val html = """<div class="prettify-tags">C</div>"""
        val node = compileHtml(html, side = "front", css = css).single() as TextNode
        assertEquals(2f, node.marginTop)
        assertEquals(1f, node.marginBottom)
    }

    @Test
    fun dividerCarriesShorthandMargin() {
        val css = ".prettify-divider--answer { margin: .7em }"
        val html = """<hr class="prettify-divider prettify-divider--answer">"""
        val node = compileHtml(html, side = "back", css = css).single() as RuleNode
        assertEquals(0.7f, node.marginTop)
        assertEquals(0.7f, node.marginBottom)
    }

    @Test
    fun noMarginClassLeavesZero() {
        val html = """<div>plain</div>"""
        val node = compileHtml(html, side = "front").single() as TextNode
        assertEquals(0f, node.marginTop)
        assertEquals(0f, node.marginBottom)
    }
}
