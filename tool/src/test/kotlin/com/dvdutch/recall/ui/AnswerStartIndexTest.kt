package com.dvdutch.recall.ui

import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.compiler.InlineFlow
import com.dvdutch.recall.compiler.compileHtml
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where reveal scrolls to: the first back node that differs from the front ([answerStartIndex]). */
class AnswerStartIndexTest {

    private fun side(html: String, side: String) = InlineFlow.apply(compileHtml(html, side))

    @Test
    fun `a FrontSide back starts its answer after the repeated question`() {
        val front = side("Which nerve innervates the diaphragm?", "front")
        val back = side("Which nerve innervates the diaphragm?<hr id=answer>Phrenic nerve", "back")
        assertEquals(1, answerStartIndex(front, back))
        assertEquals(RuleNode(), back[1])
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
}
