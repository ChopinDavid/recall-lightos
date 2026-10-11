package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headings, table cells and <pre> blocks, as laid out for the phone. */
class BlockLayoutTest {

    private fun layout(html: String) = InlineFlow.apply(compileHtml(html, "front")).map { it as TextNode }
    private fun text(node: TextNode) = node.runs.joinToString("") { it.s }

    @Test
    fun `a heading is its own bold scaled block`() {
        val nodes = layout("<h2>The Printing Press</h2>Johannes Gutenberg introduced it.")
        assertEquals(listOf("The Printing Press", "Johannes Gutenberg introduced it."), nodes.map(::text))
        assertEquals(listOf(TextRun("The Printing Press", b = true)), nodes[0].runs)
        assertEquals(1.3f, nodes[0].scale)
        assertEquals(1f, nodes[1].scale)
        assertEquals(listOf(1.5f, 1.15f), layout("<h1>a</h1><h3>b</h3>").map { it.scale })
    }

    @Test
    fun `table cells are separated and header cells bold`() {
        val nodes = layout("<table><tr><th>Expr</th><th>Result</th></tr><tr><td>7 // 2</td><td>3</td></tr></table>")
        assertEquals(listOf("Expr | Result", "7 // 2 | 3"), nodes.map(::text))
        assertEquals(listOf(TextRun("Expr", b = true), TextRun(" | "), TextRun("Result", b = true)), nodes[0].runs)
    }

    @Test
    fun `pre keeps indentation, blank runs of spaces and line breaks`() {
        val node = layout("<pre><code>for i in range(3):<br>    print(i  *  2)</code></pre>").single()
        assertEquals("for i in range(3):\n    print(i  *  2)", text(node))
        assertTrue(node.runs.all { it.pre && it.mono })
    }

    @Test
    fun `pre drops the newline after its opening tag and never rewrites math`() {
        assertEquals("  two  spaces", text(layout("<pre>\n  two  spaces\n</pre>").single()))
        assertEquals("\\(x^2\\)", text(layout("<pre>\\(x^2\\)</pre>").single()))
    }

    @Test
    fun `text after a pre block is laid out normally again`() {
        val nodes = layout("<pre>  code  </pre>  after   pre ")
        assertEquals(listOf("  code  ", "after pre"), nodes.map(::text))
    }

    @Test
    fun `furigana reads as base then small reading in parentheses`() {
        val runs = layout("<ruby>猫<rt>ねこ</rt></ruby>は<ruby>高<rp>(</rp><rt>たか</rt><rp>)</rp></ruby>い。").single().runs
        assertEquals("猫\u2060(ねこ)は高\u2060(たか)い。", runs.joinToString("") { it.s })
        // Only the readings (with their parentheses and the joiner) are small.
        assertEquals("\u2060(ねこ)\u2060(たか)", runs.filter { it.small }.joinToString("") { it.s })
    }
}
