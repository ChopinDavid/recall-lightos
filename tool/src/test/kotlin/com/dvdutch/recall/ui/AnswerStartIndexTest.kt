package com.dvdutch.recall.ui

import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.compiler.InlineFlow
import com.dvdutch.recall.compiler.compileHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Where reveal scrolls to: the first back node that differs from the front ([answerStartIndex]). */
class AnswerStartIndexTest {

    private fun side(html: String, side: String) = InlineFlow.apply(compileHtml(html, side))

    @Test
    fun `a FrontSide back starts its answer after the repeated question`() {
        val front = side("Which nerve innervates the diaphragm?", "front")
        val back = side("Which nerve innervates the diaphragm?<hr id=answer>Phrenic nerve", "back")
        assertEquals(1, answerStartIndex(front, back))
        assertEquals(RuleNode(answerDivider = true), back[1])
    }

    @Test
    fun `a cloze back starts at the top, where the answer is filled in`() {
        val front = side("""<span class="cloze">[...]</span> is first-line.<br>Extra""", "front")
        val back = side("""<span class="cloze">Metformin</span> is first-line.<br>Extra""", "back")
        assertEquals(0, answerStartIndex(front, back))
    }

    @Test
    fun `identical sides point past the end`() {
        val node = TextNode(listOf(TextRun("x")))
        assertEquals(1, answerStartIndex(listOf(node), listOf(node)))
    }

    @Test
    fun `the type-answer block goes after the answer rule, else at the boundary`() {
        val front = side("Capital of Brazil?", "front")
        val back = side("Capital of Brazil?<hr id=answer>", "back")
        assertEquals(2, typeAnswerBlockIndex(front, back))
        val noRule = side("Capital of Brazil?<br>Type it:", "back")
        assertEquals(typeAnswerBlockIndex(front, noRule), answerStartIndex(front, noRule))
    }

    @Test
    fun `a styled FrontSide wrapper on the back still counts as the question`() {
        val front = side("Which nerve innervates the diaphragm?", "front")
        val back = side("""<div style="text-align:center">Which nerve innervates the diaphragm?</div><hr id=answer>Phrenic nerve""", "back")
        assertEquals(1, answerStartIndex(front, back))
    }

    @Test
    fun `a back that puts a heading above the repeated front starts the answer after the front`() {
        val front = side("<ul><li>отступать</li><li>to retreat</li></ul>", "front")
        val back = side("<h2>Etymology</h2><ul><li>отступать</li><li>to retreat</li></ul><hr>отступа́ть", "back")
        // back = [heading, li, li, rule, answer]; the front's two nodes sit at offset 1.
        assertEquals(3, answerStartIndex(front, back))
        assertIs<RuleNode>(back[3])
    }

    @Test
    fun `the template's hr id=answer wins over any content matching`() {
        val front = side("<b>unfortunate</b>", "front")
        // A back whose layout has nothing in common with the front until its answer marker.
        val back = side("<h2>Etymology</h2><ul><li>несчастный</li></ul><div>1974</div>несча́стный<hr id=answer>unlucky", "back")
        val rule = back.indexOfFirst { it is RuleNode }
        assertEquals(rule, answerStartIndex(front, back))
        assertEquals(rule + 1, typeAnswerBlockIndex(front, back))
        // A plain <hr> is not the marker.
        assertEquals(0, answerStartIndex(front, side("x<hr>y", "back")))
    }
}
