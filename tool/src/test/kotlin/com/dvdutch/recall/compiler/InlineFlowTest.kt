package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Inline clozes read as one line, and whitespace is trimmed like a browser ([InlineFlow]). */
class InlineFlowTest {

    private fun layout(html: String, side: String) = InlineFlow.apply(compileHtml(html, side))

    private fun text(node: RenderNode): String = (node as TextNode).runs.joinToString("") { it.s }

    @Test
    fun `a cloze starting the sentence stays on its line`() {
        val front = layout(
            """<span class="cloze" data-ordinal="1">[...]</span> is first-line therapy for type 2 diabetes.""",
            "front",
        )
        assertEquals(1, front.size)
        val runs = (front.single() as TextNode).runs
        assertEquals(TextRun("[...]", b = true), runs.first())
        assertEquals("[...] is first-line therapy for type 2 diabetes.", text(front.single()))
    }

    @Test
    fun `a revealed cloze mid-sentence is bold and inline`() {
        val back = layout("""ACE inhibitors can cause a dry <span class="cloze">cough</span> due to bradykinin.""", "back")
        assertEquals(
            listOf(
                TextRun("ACE inhibitors can cause a dry "),
                TextRun("cough", b = true),
                TextRun(" due to bradykinin."),
            ),
            (back.single() as TextNode).runs,
        )
    }

    @Test
    fun `a hint shows in brackets`() {
        val front = layout("""Capital of France: <span class="cloze">[city]</span>""", "front")
        assertEquals("Capital of France: [city]", text(front.single()))
    }

    @Test
    fun `the space between two clozes survives`() {
        val back = layout("""<span class="cloze">Na⁺</span> <span class="cloze">K⁺</span> ATPase""", "back")
        assertEquals("Na⁺ K⁺ ATPase", text(back.single()))
    }

    @Test
    fun `the answer after an hr has no leading space`() {
        val back = layout("Which nerve innervates the diaphragm?\n\n<hr id=answer>\n\nPhrenic nerve (C3–C5)", "back")
        assertEquals(3, back.size)
        assertIs<RuleNode>(back[1])
        assertEquals("Phrenic nerve (C3–C5)", text(back[2]))
    }

    @Test
    fun `spaces around a line break are dropped`() {
        val node = layout("line one <br> line two ", "back").single()
        assertEquals("line one\nline two", text(node))
    }

    @Test
    fun `a cloze in a new block starts a new line`() {
        val front = layout("""<div>Pharmacology</div><span class="cloze">[...]</span> reverses opioids.""", "front")
        assertEquals(listOf("Pharmacology", "[...] reverses opioids."), front.map(::text))
    }

    @Test
    fun `doubled spaces where styled runs meet collapse`() {
        val node = layout("a <b> bold </b> c", "back").single()
        assertEquals("a bold c", text(node))
    }

    @Test
    fun `the compiler marks only inline continuations`() {
        val nodes = compileHtml("""Text <span class="cloze">x</span> more<div>next</div>""", "back")
        assertEquals(listOf(false, true, true, false), nodes.map {
            when (it) {
                is TextNode -> it.joinsPrevious
                is ClozeNode -> it.joinsPrevious
                else -> error("unexpected $it")
            }
        })
        assertTrue(nodes.none { it is ClozeNode && it.spaceBefore })
    }

    @Test
    fun `a sound inside a revealed cloze becomes a replay glyph, not text`() {
        val runs = (layout("""Say <span class="cloze">bonjour[anki:play:a:0]</span> twice""", "back").single() as TextNode).runs
        assertEquals(
            listOf(TextRun("Say "), TextRun("bonjour", b = true), TextRun("", audioTrack = 0), TextRun(" twice")),
            runs,
        )
    }
}
