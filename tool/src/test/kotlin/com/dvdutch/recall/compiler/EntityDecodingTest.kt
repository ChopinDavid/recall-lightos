package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Entity-decoding parity: libxml2 (the Python reference parser) decodes `&nbsp;`
 * to U+00A0 NO-BREAK SPACE, not U+0020. This is masked by whitespace-collapsing
 * on most paths, but is OBSERVABLE on the cloze front-hint path, which reads the
 * raw `el.text` with no whitespace cleaning.
 */
class EntityDecodingTest {

    private fun firstCloze(nodes: List<RenderNode>): ClozeNode =
        nodes.filterIsInstance<ClozeNode>().first()

    private fun allText(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) n.runs.forEach { append(it.s) }
    }

    @Test
    fun nbspInClozeHintDecodesToNoBreakSpace() {
        // Front-hint path reads raw el.text (no cleanWs); &nbsp; must be U+00A0,
        // matching libxml2. The visible glyph is "a b" with a NO-BREAK SPACE.
        val html = """<span class="cloze">[a&nbsp;b]</span>"""
        val hint = firstCloze(compileHtml(html, side = "front")).hint
        assertEquals("a b", hint)
    }

    @Test
    fun nbspInPlainTextCollapsesAfterCleanWs() {
        // Ordinary text goes through cleanWs; the U+00A0 collapses to a single
        // ASCII space, proving the U+00A0 fix does not regress the collapsed
        // path.
        val html = "a&nbsp;b"
        assertEquals("a b", allText(compileHtml(html, side = "front")))
    }
}
