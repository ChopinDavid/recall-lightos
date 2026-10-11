package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** List items: numbered `<ol>`, bulleted `<ul>`, nesting depth and `list-style-type`, as laid out for the phone. */
class ListMarkerTest {

    private fun layout(html: String, css: String = "") =
        InlineFlow.apply(compileHtml(html, "front", css)).map { it as TextNode }

    private fun text(node: TextNode) = node.runs.joinToString("") { it.s }

    @Test
    fun `an ordered list counts its items`() {
        val nodes = layout("<ol><li>one</li><li>two</li><li>three</li></ol>")
        assertEquals(listOf("1. one", "2. two", "3. three"), nodes.map(::text))
        // The marker is its own run ahead of the text, never merged into it.
        assertEquals(listOf(TextRun("1. ", marker = true), TextRun("one")), nodes[0].runs)
        assertTrue(nodes.all { it.indent == 1 })
    }

    @Test
    fun `start, type and value attributes number like a browser`() {
        assertEquals(listOf("4. a", "5. b"), layout("<ol start=4><li>a</li><li>b</li></ol>").map(::text))
        assertEquals(listOf("a. x", "b. y"), layout("<ol type='a'><li>x</li><li>y</li></ol>").map(::text))
        assertEquals(listOf("I. x", "II. y"), layout("<ol type='I'><li>x</li><li>y</li></ol>").map(::text))
        assertEquals(listOf("1. x", "7. y", "8. z"), layout("<ol><li>x</li><li value=7>y</li><li>z</li></ol>").map(::text))
    }

    @Test
    fun `bulleted lists nest as a browser does and step in per level`() {
        val nodes = layout("<ul><li>one</li><li>two<ul><li>two-a</li><ul><li>deep</li></ul></ul></li></ul>")
        assertEquals(listOf("• one", "• two", "◦ two-a", "▪ deep"), nodes.map(::text))
        assertEquals(listOf(1, 1, 2, 3), nodes.map { it.indent })
    }

    @Test
    fun `an ordered list inside a bulleted one counts from one each time`() {
        val nodes = layout("<ul><li>a<ol><li>x</li></ol></li><li>b<ol><li>y</li><li>z</li></ol></li></ul>")
        assertEquals(listOf("• a", "1. x", "• b", "1. y", "2. z"), nodes.map(::text))
    }

    @Test
    fun `list-style-type from the deck CSS or an inline style replaces the marker`() {
        // The Russian Core 5000 etymology lists: a bold unmarked head word, circled parts.
        val css = "li.h { list-style-type: none; font-weight: bold } li.p { list-style-type: circle } .sq { list-style: square inside }"
        val nodes = layout(
            "<ul><li class='h'>этот</li><li class='p'>this one</li><li class='sq'>s</li>" +
                "<li style='list-style-type: upper-roman'>r</li></ul>",
            css,
        )
        assertEquals(listOf("этот", "◦ this one", "▪ s", "IV. r"), nodes.map(::text))
        // An unmarked item is still a list item: it keeps its level, with no marker run.
        assertNull(nodes[0].runs.firstOrNull { it.marker })
        assertEquals(1, nodes[0].indent)
    }

    @Test
    fun `a list's own style applies to its items`() {
        assertEquals(listOf("a. x", "b. y"), layout("<ol style='list-style-type: lower-alpha'><li>x</li><li>y</li></ol>").map(::text))
        assertEquals(listOf("x"), layout("<ul class='plain'><li>x</li></ul>", ".plain { list-style: none }").map(::text))
        // CSS beats the type attribute.
        assertEquals(listOf("1. x"), layout("<ol type='a' style='list-style-type: decimal'><li>x</li></ol>").map(::text))
    }

    @Test
    fun `a block inside an item keeps the marker on the item's first line`() {
        val nodes = layout("<ul><li><div>text</div></li><li>a<div>b</div></li></ul>")
        assertEquals(listOf("• text", "• a", "b"), nodes.map(::text))
        assertEquals(listOf(1, 1, 1), nodes.map { it.indent })
    }

    @Test
    fun `pretty-printed whitespace between an item and its first block changes nothing`() {
        val nodes = layout("<ul>\n  <li>\n    <p>text</p>\n  </li>\n  <li>\n    <p>a</p>\n    <p>b</p>\n  </li>\n</ul>")
        assertEquals(listOf("• text", "• a", "b"), nodes.map(::text))
    }

    @Test
    fun `an invisible item still takes its number, a display none one does not`() {
        val css = ".hidden { visibility: hidden } .gone { display: none }"
        val nodes = layout("<ol><li>a</li><li class='hidden'>b</li><li style='display: none'>c</li><li class='gone'>d</li><li>e</li></ol>", css)
        assertEquals(listOf("1. a", "3. e"), nodes.map(::text))
        // An invisible item's value= still sets the count.
        assertEquals(listOf("1. a", "6. c"), layout("<ol><li>a</li><li value=5 class='hidden'>b</li><li>c</li></ol>", css).map(::text))
    }

    @Test
    fun `a list opening inside an item leaves the item's bare marker on its own line`() {
        val nodes = layout("<ul><li><ul><li>x</li></ul></li></ul>")
        assertEquals(listOf("•", "◦ x"), nodes.map(::text))
        assertEquals(listOf(1, 2), nodes.map { it.indent })
        assertEquals(listOf(1, 1), nodes.map { n -> n.runs.count { it.marker } })
    }

    @Test
    fun `text outside any list has no indent and a stray item gets a bullet`() {
        val nodes = layout("before<ul><li>x</li></ul>after<li>stray</li>")
        assertEquals(listOf("before", "• x", "after", "• stray"), nodes.map(::text))
        assertEquals(listOf(0, 1, 0, 0), nodes.map { it.indent })
    }

    @Test
    fun `an item with a cloze keeps its marker through the layout pass`() {
        val node = layout("<ul><li><span class=\"cloze\">[...]</span> is it</li></ul>").single()
        assertEquals(listOf(TextRun("• ", marker = true), TextRun("[...]", b = true), TextRun(" is it")), node.runs)
        assertEquals(1, node.indent)
    }

    @Test
    fun `parity serialization sees the marker as part of the item text`() {
        val json = nodesToJson(compileHtml("<ul><li>one</li></ul>", "front")).toString()
        assertEquals("""[{"t":"text","runs":[{"s":"• one"}]}]""", json)
    }
}
