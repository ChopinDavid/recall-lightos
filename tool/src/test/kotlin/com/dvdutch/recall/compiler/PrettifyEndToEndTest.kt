package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * End-to-end parity for the "anki-prettify" Russian Core 5000 front side: the
 * CSS hide-class drop (Fix 1) and the hierarchical-tag leaf (Fix 2) together.
 *
 * The FRONT wraps the Index (`10`) and `D {{Dispersion}}` (`D 100`) in
 * `class="hidden"`, and the notetype CSS declares `.hidden { visibility: hidden }`,
 * so AnkiDroid paints neither — nor may we. The `{{Tags}}` value
 * `russiancore5000::C` prints inside `.prettify-tags` and AnkiDroid's `<script>`
 * reduces it to the leaf `C`.
 */
class PrettifyEndToEndTest {

    private val css = """
        .hidden { visibility: hidden; }
        .prettify-field { font-weight: bold; }
    """.trimIndent()

    private val frontHtml = """
        <div class="prettify-word">а</div>
        <div class="prettify-field prettify-field--index hidden">10</div>
        <div class="prettify-field prettify-field--dispersion hidden">D 100</div>
        <div class="prettify-tags">russiancore5000::C</div>
    """.trimIndent()

    private fun text(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) {
            if (isNotEmpty()) append('\n')
            n.runs.forEach { append(it.s) }
        }
    }

    @Test
    fun frontHidesIndexAndDispersionAndShowsTagLeaf() {
        val out = text(
            compileHtml(frontHtml, side = "front", css = css, tags = listOf("russiancore5000::C")),
        ).trim()
        assertFalse(out.contains("10"), "Index must be hidden by .hidden, got: $out")
        assertFalse(out.contains("D 100"), "Dispersion must be hidden by .hidden, got: $out")
        assertFalse(
            out.contains("russiancore5000::C"),
            "hierarchical tag must render as leaf, got: $out",
        )
        assertEquals("а\nC", out)
    }
}
