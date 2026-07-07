package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 1 (minimal flex-row) + Feature 2 (text-align) end to end through
 * [compileHtml], modelled on the Russian Core 5000 / anki-prettify header.
 */
class FlexRowTest {

    private fun flatText(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) {
            if (isNotEmpty()) append('\n')
            n.runs.forEach { append(it.s) }
        }
    }

    // --- Feature 1: flex detection & fallback --------------------------------

    @Test
    fun flexClassCompilesToRow() {
        val css = ".row { display: flex }"
        val html = """
            <div class="row"><div>10</div><div>C</div><div>D 100</div></div>
        """.trimIndent()
        val out = compileHtml(html, side = "back", css = css)
        val row = out.single() as RowNode
        assertEquals(3, row.cells.size)
    }

    @Test
    fun rowCellWeightsAndAligns() {
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>A</div><div>B</div><div>C</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        // first: wrap-content, start
        assertNull(row.cells[0].weight)
        assertEquals(BlockAlign.START, row.cells[0].align)
        // middle: weight 1, center
        assertEquals(1f, row.cells[1].weight)
        assertEquals(BlockAlign.CENTER, row.cells[1].align)
        // last: wrap-content, end
        assertNull(row.cells[2].weight)
        assertEquals(BlockAlign.END, row.cells[2].align)
    }

    @Test
    fun twoChildrenRowHasNoMiddle() {
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>A</div><div>B</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        assertEquals(2, row.cells.size)
        assertNull(row.cells[0].weight)
        assertEquals(BlockAlign.START, row.cells[0].align)
        assertNull(row.cells[1].weight)
        assertEquals(BlockAlign.END, row.cells[1].align)
    }

    @Test
    fun tooManyChildrenFallsBackToVertical() {
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>A</div><div>B</div><div>C</div><div>D</div><div>E</div></div>"""
        val out = compileHtml(html, side = "back", css = css)
        assertTrue(out.none { it is RowNode }, "5 children must fall back: $out")
        assertEquals("A\nB\nC\nD\nE", flatText(out))
    }

    @Test
    fun looseTextFallsBackToVertical() {
        val css = ".row { display: flex }"
        val html = """<div class="row">loose<div>A</div><div>B</div></div>"""
        val out = compileHtml(html, side = "back", css = css)
        assertTrue(out.none { it is RowNode }, "loose text must fall back: $out")
        assertTrue(flatText(out).contains("loose"))
    }

    @Test
    fun whitespaceOnlyLooseTextStillRow() {
        val css = ".row { display: flex }"
        val html = "<div class=\"row\">\n  <div>A</div>\n  <div>B</div>\n</div>"
        val out = compileHtml(html, side = "back", css = css)
        assertTrue(out.single() is RowNode, "whitespace between cells is fine: $out")
    }

    @Test
    fun flexColumnFallsBackToVertical() {
        val css = ".row { display: flex; flex-direction: column }"
        val html = """<div class="row"><div>A</div><div>B</div></div>"""
        val out = compileHtml(html, side = "back", css = css)
        assertTrue(out.none { it is RowNode }, "column must fall back: $out")
        assertEquals("A\nB", flatText(out))
    }

    @Test
    fun oneChildFallsBackToVertical() {
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>A</div></div>"""
        val out = compileHtml(html, side = "back", css = css)
        assertTrue(out.none { it is RowNode }, "single child must fall back: $out")
    }

    @Test
    fun hrInsideCellRendersAsRule() {
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>A</div><div>x<hr>y</div><div>B</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        assertTrue(row.cells[1].nodes.any { it is RuleNode }, "hr should span the cell")
    }

    // --- Fix A: flex: <number> cell weights ----------------------------------

    @Test
    fun allExplicitFlexWeightsAllCells() {
        // .25 / 1 / .25 header: every cell gets its flex as a weight, so the corners
        // are equal and the centre is truly centred (flex-basis-0 semantics).
        val css = """
            .row { display: flex }
            .left-text { flex: .25; text-align: left }
            .center-text { flex: 1; text-align: center }
            .right-text { flex: .25; text-align: right }
        """.trimIndent()
        val html = """<div class="row"><div class="left-text">8</div><div class="center-text">P</div><div class="right-text">D 100</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        assertEquals(0.25f, row.cells[0].weight)
        assertEquals(1f, row.cells[1].weight)
        assertEquals(0.25f, row.cells[2].weight)
        // per-cell text-align still honored
        assertEquals(BlockAlign.START, row.cells[0].align)
        assertEquals(BlockAlign.CENTER, row.cells[1].align)
        assertEquals(BlockAlign.END, row.cells[2].align)
    }

    @Test
    fun mixedExplicitFlexFallsBackToPositionalWeights() {
        // Not ALL cells carry an explicit flex number -> keep the positional default
        // (corners wrap-content/null, middle weight 1).
        val css = """
            .row { display: flex }
            .center-text { flex: 1; text-align: center }
        """.trimIndent()
        val html = """<div class="row"><div>8</div><div class="center-text">P</div><div>D 100</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        assertNull(row.cells[0].weight)
        assertEquals(1f, row.cells[1].weight)
        assertNull(row.cells[2].weight)
    }

    @Test
    fun explicitFlexHonorsPerCellTextAlign() {
        // A cell's own class text-align wins over the positional default even under
        // weighted layout (e.g. a left-aligned first cell stays START).
        val css = """
            .row { display: flex }
            .a { flex: 1; text-align: center }
            .b { flex: 1; text-align: center }
        """.trimIndent()
        val html = """<div class="row"><div class="a">x</div><div class="b">y</div></div>"""
        val row = compileHtml(html, side = "back", css = css).single() as RowNode
        assertEquals(BlockAlign.CENTER, row.cells[0].align)
        assertEquals(BlockAlign.CENTER, row.cells[1].align)
    }

    // --- Feature 2: text-align on block nodes --------------------------------

    @Test
    fun inlineStyleCentersBlock() {
        val html = """<div style="text-align: center">hi</div>"""
        val node = compileHtml(html, side = "front").single() as TextNode
        assertEquals(BlockAlign.CENTER, node.align)
    }

    @Test
    fun classCssCentersBlock() {
        val css = ".c { text-align: center }"
        val html = """<div class="c">hi</div>"""
        val node = compileHtml(html, side = "front", css = css).single() as TextNode
        assertEquals(BlockAlign.CENTER, node.align)
    }

    @Test
    fun alignInheritsToChildBlocks() {
        val css = ":root { --a: center } .flash { text-align: var(--a) }"
        val html = """<div class="flash"><div>a</div><div>b</div></div>"""
        val nodes = compileHtml(html, side = "front", css = css)
        assertTrue(nodes.all { it is TextNode && it.align == BlockAlign.CENTER }, "$nodes")
    }

    // --- Fix B: block placement follows PARENT alignment ---------------------

    @Test
    fun inlineBlockPlacementFollowsParentAlignment() {
        // `.audio-container { display: inline-block; text-align: left }` inside a
        // center parent: CSS centres the inline-block within its parent (placement),
        // its own text-align only governs internal content. So the sentence text
        // must be CENTER-placed, not left.
        val css = """
            .sentence { text-align: center }
            .audio-container { display: inline-block; text-align: left }
        """.trimIndent()
        val html = """<div class="sentence"><div class="audio-container">Я надеялся</div></div>"""
        val node = compileHtml(html, side = "back", css = css).single() as TextNode
        assertEquals(BlockAlign.CENTER, node.align)
    }

    @Test
    fun inlineBlockOwnAlignStillAppliesWhenParentIsStart() {
        // Without a centering parent, an inline-block still just inherits START; its
        // own left text-align is a no-op placement-wise.
        val css = ".audio-container { display: inline-block; text-align: left }"
        val html = """<div><div class="audio-container">x</div></div>"""
        val node = compileHtml(html, side = "back", css = css).single() as TextNode
        assertEquals(BlockAlign.START, node.align)
    }

    @Test
    fun nonInlineBlockOwnAlignStillWins() {
        // A regular (non-inline-block) block's own text-align continues to win over
        // the parent (unchanged behaviour) — e.g. a left field under a center card.
        val css = """
            .card { text-align: center }
            .left { text-align: left }
        """.trimIndent()
        val html = """<div class="card"><div class="left">x</div></div>"""
        val node = compileHtml(html, side = "back", css = css).single() as TextNode
        assertEquals(BlockAlign.START, node.align)
    }

    // --- end-to-end prettify header ------------------------------------------

    @Test
    fun prettifyHeaderBackCompilesToCenteredRow() {
        val css = """
            :root { --card-text-align: center; }
            .prettify-flashcard { text-align: var(--card-text-align); }
            .header-container { display: flex; justify-content: space-between; }
            .center-text { flex: 1; text-align: center; }
            .left-text { text-align: left; }
            .right-text { text-align: right; }
            .hidden { visibility: hidden; }
        """.trimIndent()
        // back side: corners visible (no .hidden on them)
        val html = """
            <div class="prettify-flashcard">
              <div class="header-container">
                <div class="left-text">10</div>
                <div class="center-text">
                  <div class="prettify-tags">C</div>
                  <div class="prettify-field prettify-field--front">приве́т</div>
                  <hr id="answer">
                  <div class="prettify-field prettify-field--back">hello</div>
                </div>
                <div class="right-text">D 100</div>
              </div>
            </div>
        """.trimIndent()
        val out = compileHtml(html, side = "back", css = css)
        val row = out.filterIsInstance<RowNode>().single()
        assertEquals(3, row.cells.size)
        // left cell = 10
        assertEquals("10", flatText(row.cells[0].nodes).trim())
        // right cell = D 100
        assertEquals("D 100", flatText(row.cells[2].nodes).trim())
        // center cell holds tag/word/translation, all centered, and the divider.
        val center = row.cells[1].nodes
        assertTrue(center.any { it is RuleNode }, "center keeps its divider")
        val centerText = center.filterIsInstance<TextNode>()
        assertTrue(centerText.any { flatText(listOf(it)).contains("приве́т") })
        assertTrue(
            centerText.all { it.align == BlockAlign.CENTER },
            "tag/word/translation centered: ${centerText.map { it.align }}",
        )
    }

    @Test
    fun prettifyHeaderFrontHidesCornersContentCentered() {
        val css = """
            :root { --card-text-align: center; }
            .header-container { display: flex; }
            .center-text { text-align: center; }
            .hidden { visibility: hidden; }
        """.trimIndent()
        val html = """
            <div class="header-container">
              <div class="left-text hidden">10</div>
              <div class="center-text">
                <div class="prettify-field">приве́т</div>
              </div>
              <div class="right-text hidden">D 100</div>
            </div>
        """.trimIndent()
        val out = compileHtml(html, side = "front", css = css)
        val row = out.filterIsInstance<RowNode>().single()
        // three cells still (structure preserved) but corners empty (hidden)
        assertEquals(3, row.cells.size)
        assertTrue(flatText(row.cells[0].nodes).isBlank(), "left hidden on front")
        assertTrue(flatText(row.cells[2].nodes).isBlank(), "right hidden on front")
        assertTrue(flatText(row.cells[1].nodes).contains("приве́т"))
    }
}
