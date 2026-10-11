package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.DetailsNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `<details>`/`<summary>` compile to a collapsible [DetailsNode], closed unless `open`. */
class DetailsTest {

    private fun layout(html: String, side: String = "back", css: String = "") =
        InlineFlow.apply(compileHtml(html, side, css))

    private fun text(node: RenderNode) = (node as TextNode).runs.joinToString("") { it.s }

    @Test
    fun `a details block is closed, with its summary and body, and the text after it follows`() {
        val nodes = layout("<details><summary>Etymology</summary><ul><li>этот</li></ul></details>after")
        val details = assertIs<DetailsNode>(nodes[0])
        assertFalse(details.open)
        assertEquals(listOf(TextRun("Etymology")), details.summary)
        assertEquals(listOf("• этот"), details.body.map(::text))
        assertEquals("after", text(nodes[1]))
    }

    @Test
    fun `the open attribute opens it`() {
        val details = assertIs<DetailsNode>(layout("<details open><summary>S</summary>b</details>").single())
        assertTrue(details.open)
        assertEquals(listOf("b"), details.body.map(::text))
    }

    @Test
    fun `summary formatting is kept and the text after the summary is body`() {
        val details = assertIs<DetailsNode>(layout("<details><summary><b>Ety</b>mology</summary> history <br>line</details>").single())
        assertEquals(listOf(TextRun("Ety", b = true), TextRun("mology")), details.summary)
        assertEquals(listOf("history\nline"), details.body.map(::text))
    }

    @Test
    fun `a hidden summary on a closed block drops the block, like the Russian front`() {
        val css = ".hidden { visibility: hidden }"
        val nodes = layout("<details class='details-hidden'><summary class='hidden'></summary>secret</details><div>Word</div>", "front", css)
        assertEquals(listOf("Word"), nodes.map(::text))
        val inline = layout("<details><summary style='display: none'></summary>secret</details>Word", "front")
        assertEquals(listOf("Word"), inline.map(::text))
    }

    @Test
    fun `an open block with a hidden summary is just its content`() {
        val nodes = layout("<details open><summary class='hidden'>s</summary>shown<div>block</div></details>after", css = ".hidden { visibility: hidden }")
        assertEquals(listOf("shown", "block", "after"), nodes.map(::text))
    }

    @Test
    fun `a visible but empty summary keeps the block, as a bare chevron`() {
        val details = assertIs<DetailsNode>(layout("<details><summary></summary>b</details>").single())
        assertEquals(emptyList(), details.summary)
        assertEquals(listOf("b"), details.body.map(::text))
    }

    @Test
    fun `a cloze in the summary reads inline`() {
        val front = assertIs<DetailsNode>(layout("<details><summary><span class=\"cloze\">[...]</span> origin</summary>b</details>", "front").single())
        assertEquals(listOf(TextRun("[...]", b = true), TextRun(" origin")), front.summary)
        val back = assertIs<DetailsNode>(layout("<details><summary><span class=\"cloze\">Latin</span></summary>b</details>").single())
        assertEquals(listOf(TextRun("Latin", b = true)), back.summary)
    }

    @Test
    fun `a details without a summary is labelled like a browser's`() {
        val details = assertIs<DetailsNode>(layout("<details>x</details>").single())
        assertEquals(listOf(TextRun("Details")), details.summary)
        assertEquals(listOf("x"), details.body.map(::text))
    }

    @Test
    fun `nested details stay nested`() {
        val outer = assertIs<DetailsNode>(layout("<details><summary>a</summary><details><summary>b</summary>c</details></details>").single())
        val inner = assertIs<DetailsNode>(outer.body.single())
        assertEquals(listOf(TextRun("b")), inner.summary)
        assertEquals(listOf("c"), inner.body.map(::text))
    }

    @Test
    fun `a cloze inside the body reads inline`() {
        val details = assertIs<DetailsNode>(layout("<details><summary>s</summary>The <span class=\"cloze\">answer</span> here</details>").single())
        val body = assertIs<TextNode>(details.body.single())
        assertEquals(listOf(TextRun("The "), TextRun("answer", b = true), TextRun(" here")), body.runs)
    }

    @Test
    fun `the summary line takes its class font size, the body its own`() {
        // The Russian Core 5000 summary: `prettify-field--form` is half the card base.
        val css = ".prettify-flashcard { font-size: 28px } .prettify-field--form { font-size: 14px } .big { font-size: 42px }"
        val html = "<div class='prettify-flashcard'><details><summary class='prettify-field prettify-field--form'>Etymology</summary>" +
            "<div class='big'>body</div></details></div>"
        val details = assertIs<DetailsNode>(layout(html, css = css).single())
        assertEquals(0.5f, details.scale)
        assertEquals(1.5f, assertIs<TextNode>(details.body.single()).scale)
        // No class size: the summary reads at the card's base size.
        assertEquals(1f, assertIs<DetailsNode>(layout("<details><summary>s</summary>b</details>").single()).scale)
    }

    @Test
    fun `blocks inside the summary read as one line`() {
        val details = assertIs<DetailsNode>(layout("<details><summary><div>Etymology</div><div>(tap)</div></summary>b</details>").single())
        assertEquals(listOf(TextRun("Etymology"), TextRun(" "), TextRun("(tap)")), details.summary)
    }

    @Test
    fun `a details inside a list item carries the item's marker and level`() {
        val nodes = layout("<ul><li><details><summary>Translation</summary>этот</details></li><li>next</li></ul>")
        val details = assertIs<DetailsNode>(nodes[0])
        assertEquals(listOf(TextRun("• ", marker = true), TextRun("Translation")), details.summary)
        assertEquals(1, details.indent)
        assertEquals(listOf("этот"), details.body.map(::text))
        assertEquals("• next", text(nodes[1]))
        // A details at the top level is at no list level.
        assertEquals(0, assertIs<DetailsNode>(layout("<details><summary>s</summary>b</details>").single()).indent)
    }

    @Test
    fun `loose body text reads at the section's font size`() {
        val css = ".prettify-flashcard { font-size: 28px } .prettify-field--form { font-size: 14px }"
        val html = "<div class='prettify-flashcard'><div class='prettify-field--form'>" +
            "<details open><summary>Etymology</summary>from Latin <b>x</b></details></div></div>"
        val details = assertIs<DetailsNode>(layout(html, css = css).single())
        assertEquals(0.5f, details.scale)
        assertEquals(0.5f, assertIs<TextNode>(details.body.single()).scale)
    }

    @Test
    fun `a list inside a section inside a list item is styled at its real depth`() {
        val outer = layout("<ul><li><details open><summary>S</summary><ul><li>x</li></ul></details></li></ul>")
        val details = assertIs<DetailsNode>(outer.single())
        val item = assertIs<TextNode>(details.body.single())
        assertEquals("◦ x", text(item))
        // Indented relative to the section, which already sits at the item's level.
        assertEquals(1, item.indent)
    }

    @Test
    fun `a section with a hidden summary leaves its item's marker where a browser puts it`() {
        // Open: the section's content is the item's first line, under the item's marker.
        val open = layout("<ol><li>a</li><li><details open><summary style='display: none'>s</summary>b</details></li><li>c</li></ol>")
        assertEquals(listOf("1. a", "2. b", "3. c"), open.map(::text))
        assertEquals(listOf(1, 1, 1), open.map { (it as TextNode).indent })
        // Closed: nothing of it shows, and the item's marker heads what follows it.
        val closed = layout("<ol><li>a</li><li><details><summary style='display: none'>s</summary>secret</details>b</li><li>c</li></ol>")
        assertEquals(listOf("1. a", "2. b", "3. c"), closed.map(::text))
    }

    @Test
    fun `the summary line takes its own or the enclosing text-align`() {
        val centered = assertIs<DetailsNode>(layout("<div style='text-align: center'><details><summary>s</summary>b</details></div>").single())
        assertEquals(BlockAlign.CENTER, centered.align)
        val own = assertIs<DetailsNode>(layout("<div style='text-align: center'><details><summary style='text-align: right'>s</summary>b</details></div>").single())
        assertEquals(BlockAlign.END, own.align)
        assertEquals(BlockAlign.START, assertIs<DetailsNode>(layout("<details><summary>s</summary>b</details>").single()).align)
    }

    @Test
    fun `a section nested in a summary gives its text to the summary line`() {
        // A closed one only its summary: its body stays hidden.
        val closed = assertIs<DetailsNode>(layout("<details><summary>a<details><summary>b</summary>c</details></summary>d</details>").single())
        assertEquals("a b", closed.summary!!.joinToString("") { it.s })
        assertEquals(listOf("d"), closed.body.map(::text))
        val open = assertIs<DetailsNode>(layout("<details><summary>a<details open><summary>b</summary>c</details></summary>d</details>").single())
        assertEquals("a b c", open.summary!!.joinToString("") { it.s })
    }

    @Test
    fun `the summary's whitespace is trimmed`() {
        val details = assertIs<DetailsNode>(layout("<details><summary>  Etymology </summary>b</details>").single())
        assertEquals(listOf(TextRun("Etymology")), details.summary)
    }
}
