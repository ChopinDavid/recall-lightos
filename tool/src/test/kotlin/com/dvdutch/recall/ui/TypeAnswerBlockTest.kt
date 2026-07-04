package com.dvdutch.recall.ui

import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.study.TypeAnswerReveal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [typeAnswerRevealNode] — the mapping from a [TypeAnswerReveal] to the
 * single render node the back's set-off type-answer block displays. A typed reveal shows
 * the backend diff node verbatim; an untyped reveal shows a plain "answer: X" line (never
 * a fabricated diff).
 */
class TypeAnswerBlockTest {

    @Test
    fun `a diff reveal passes its node through unchanged`() {
        val diffNode = TextNode(listOf(TextRun("p", mono = true, strike = true)))
        val node = typeAnswerRevealNode(TypeAnswerReveal.Diff(diffNode))
        assertEquals(diffNode, node)
    }

    @Test
    fun `an expected reveal renders a plain answer line`() {
        val node = typeAnswerRevealNode(TypeAnswerReveal.Expected("Paris")) as TextNode
        val text = node.runs.joinToString("") { it.s }
        assertTrue("Paris" in text, "the expected answer must appear: $text")
        // Not a diff: no strike/underline flags on the plain line.
        assertTrue(node.runs.none { it.strike || it.underline }, "expected line must not look like a diff")
    }
}
