package com.dvdutch.recall.ui

import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fix D — the gap inserted BEFORE each stacked block (em). The first block's top
 * margin stands alone; between two blocks the previous block's bottom margin and
 * the next block's top margin COLLAPSE to their max (CSS collapse), not their sum.
 */
class MarginCollapseTest {

    private fun text(top: Float, bottom: Float) =
        TextNode(listOf(TextRun("x")), marginTop = top, marginBottom = bottom)

    @Test
    fun firstBlockKeepsItsTopMargin() {
        val gaps = collapsedTopGapsEm(listOf(text(2f, 1f)))
        assertEquals(listOf(2f), gaps)
    }

    @Test
    fun betweenBlocksCollapsesToMax() {
        // prev bottom 1em, next top 2em -> gap 2em (max, not 3em sum).
        val gaps = collapsedTopGapsEm(listOf(text(0f, 1f), text(2f, 0f)))
        assertEquals(listOf(0f, 2f), gaps)
    }

    @Test
    fun collapseTakesLargerOfTheTwo() {
        val gaps = collapsedTopGapsEm(listOf(text(0f, 3f), text(1f, 0f)))
        assertEquals(listOf(0f, 3f), gaps)
    }

    @Test
    fun ruleNodeMarginsParticipate() {
        // divider .7em bottom, next block 0 top -> .7em gap before next.
        val gaps = collapsedTopGapsEm(listOf(RuleNode(marginTop = 0f, marginBottom = 0.7f), text(0f, 0f)))
        assertEquals(listOf(0f, 0.7f), gaps)
    }

    @Test
    fun emptyListNoGaps() {
        assertEquals(emptyList(), collapsedTopGapsEm(emptyList()))
    }
}
