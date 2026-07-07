package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fix 2: a hierarchical note tag (`a::b::c`) that the template prints via
 * `{{Tags}}` must render as its LEAF segment (`c`), matching AnkiDroid's
 * `<script>`-driven prettify-tags behaviour (which we can't run).
 *
 * The mapping is EXACT-MATCH against the note's real tags only: a whitespace
 * token that equals one of the passed tags AND contains `::` becomes the text
 * after the last `::`. Any other `::`-bearing text (cloze content, prose) is
 * left untouched, so we never blanket-split on `::`.
 */
class TagLeafTest {

    private fun text(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) n.runs.forEach { append(it.s) }
    }

    @Test
    fun hierarchicalTagRendersAsLeaf() {
        val html = """<div class="prettify-tags">russiancore5000::C</div>"""
        val out = text(compileHtml(html, side = "front", tags = listOf("russiancore5000::C")))
        assertEquals("C", out.trim())
    }

    @Test
    fun exactMatchOnly_nonTagDoubleColonUntouched() {
        // The `::` text is NOT one of the note's tags → must survive verbatim.
        val html = """<div>see foo::bar for details</div>"""
        val out = text(compileHtml(html, side = "front", tags = listOf("russiancore5000::C")))
        assertTrue(out.contains("foo::bar"), "non-tag :: text must be untouched, got: $out")
    }

    @Test
    fun noTagsPassed_leavesTextUntouched() {
        val html = """<div class="prettify-tags">russiancore5000::C</div>"""
        val out = text(compileHtml(html, side = "front"))
        assertTrue(out.contains("russiancore5000::C"), "no tags → verbatim, got: $out")
    }

    @Test
    fun multipleTagsInOneNode_eachMappedToLeaf() {
        val html = """<div class="prettify-tags">deck::a other::b::leaf</div>"""
        val out = text(
            compileHtml(html, side = "front", tags = listOf("deck::a", "other::b::leaf")),
        )
        assertEquals("a leaf", out.trim())
    }

    @Test
    fun flatTagWithoutDoubleColon_untouched() {
        // A tag with no `::` has no leaf to extract; it renders verbatim.
        val html = """<div class="prettify-tags">marked</div>"""
        val out = text(compileHtml(html, side = "front", tags = listOf("marked")))
        assertEquals("marked", out.trim())
    }

    @Test
    fun partialMatchTokenNotRewritten() {
        // Token `russiancore5000::CD` is NOT an exact tag → left alone even though
        // `russiancore5000::C` is a tag.
        val html = """<div>russiancore5000::CD</div>"""
        val out = text(compileHtml(html, side = "front", tags = listOf("russiancore5000::C")))
        assertTrue(out.contains("russiancore5000::CD"), "partial match must not rewrite, got: $out")
    }

    @Test
    fun mixedTagAndProseInOneNode() {
        // Only the exact-match token becomes a leaf; surrounding prose is kept.
        val html = """<div>tag russiancore5000::C here</div>"""
        val out = text(compileHtml(html, side = "front", tags = listOf("russiancore5000::C")))
        assertEquals("tag C here", out.trim())
    }
}
