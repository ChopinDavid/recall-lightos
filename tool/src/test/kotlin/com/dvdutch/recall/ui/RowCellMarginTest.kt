package com.dvdutch.recall.ui

import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.compiler.compileHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 2 — ROOT CAUSE of the oversized gap between the translation ("he") and the
 * SECOND (top-level) divider on the Russian Core 5000 back.
 *
 * The header is a `display:flex` row (`RowNode`); its centre cell stacks
 * `tags → word → <hr divider> → translation`. That FIRST divider (`.prettify-divider
 * --answer`, `margin: .7em`) sits INSIDE the cell, while the SECOND divider is a
 * TOP-LEVEL node. [RenderNodeColumn] inserts the collapsed em gaps ([collapsedTopGapsEm])
 * between top-level nodes, so the second divider gets its full .7em top gap — but the
 * cell used a plain `Column` that DROPPED every in-cell margin, so the first divider
 * got only its intrinsic rule padding. The result: the gap BELOW "he" (top-level,
 * .7em) is ~2× the gap ABOVE "he" (in-cell, no margin) — the "oversized" asymmetry.
 *
 * The fix makes the cell stack with the SAME collapsed-margin gaps. This test pins the
 * evidence: the cell's divider carries the .7em margin, and [collapsedTopGapsEm] over
 * the cell's nodes inserts that SAME gap the top-level column would — so both dividers
 * are spaced identically once the cell honors it.
 */
class RowCellMarginTest {

    // Trimmed to the header structure that reproduces the bug (verified against the
    // real deck's compiled node tree).
    private val css = """
        .header-container { display: flex; justify-content: space-between; }
        .center-text { flex: 1; text-align: center; }
        .left-text { flex: .25; text-align: left; margin-top: 1em; }
        .right-text { flex: .25; text-align: right; margin-top: 1em; }
        .prettify-divider--answer { margin: .7em; }
        .prettify-tags { margin-top: 2em; margin-bottom: 1em; }
    """.trimIndent()

    private val html = """
        <div class="header-container">
          <div class="left-text">8</div>
          <div class="center-text">
            <div class="prettify-tags">P</div>
            <div class="prettify-field prettify-field--front">он</div>
            <hr class="prettify-divider prettify-divider--answer" />
            <div class="prettify-field prettify-field--back">he</div>
          </div>
          <div class="right-text">D 100</div>
        </div>
        <hr class="prettify-divider prettify-divider--answer" />
        <div class="prettify-field prettify-field--sentence">S</div>
    """.trimIndent()

    @Test
    fun centerCellDividerCarriesSameMarginAsTopLevelDivider() {
        val nodes = compileHtml(html, side = "back", css = css)
        val row = nodes.first { it is RowNode } as RowNode
        val center = row.cells[1]
        // Gaps the cell's own stacking must insert (the fix uses this exact function).
        val cellGaps = collapsedTopGapsEm(center.nodes)
        // center cell nodes: [tags, word, divider, translation].
        // The divider is index 2; the gap before it must be the .7em margin, NOT 0.
        assertEquals(0.7f, cellGaps[2], 1e-4f, "in-cell divider must get its .7em top gap")

        // The top-level divider (after the row) gets .7em too — so both are equal.
        val topGaps = collapsedTopGapsEm(nodes)
        val dividerIdx = nodes.indexOfFirst { it is com.dvdutch.recall.api.RuleNode }
        assertEquals(
            cellGaps[2],
            topGaps[dividerIdx],
            1e-4f,
            "the two dividers must be spaced identically (symmetric gaps around 'he')",
        )
    }

    @Test
    fun cellGapsAreTightNoRunawaySpacing() {
        // Guardrail against the diagnosed cause: no gap inside the cell exceeds the
        // deck's own declared margins (nothing pathological/oversized).
        val nodes = compileHtml(html, side = "back", css = css)
        val row = nodes.first { it is RowNode } as RowNode
        for (cell in row.cells) {
            for (g in collapsedTopGapsEm(cell.nodes)) {
                assertTrue(g <= 2f, "cell gap $g must stay bounded by declared margins")
            }
        }
    }
}
