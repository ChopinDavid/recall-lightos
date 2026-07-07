package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Inline per-sound replay: the backend's `extractAvTags(...).text` rewrites each
 * `[sound:x]` into a positional `[anki:play:q:N]` / `[anki:play:a:N]` marker where
 * N is the 0-based index into that side's ordered audio list. [compileHtml] must
 * turn each such marker — wherever it sits inline in the text — into a [TextRun]
 * whose [TextRun.audioTrack] carries N (empty visible text), so the UI can place a
 * tappable speaker glyph AT THE MARKER'S POSITION and play exactly that track.
 *
 * The visible text around the marker is preserved byte-for-byte (minus the marker
 * itself); a marker never leaks as literal text.
 */
class InlineAudioTest {

    private fun runsOf(nodes: List<RenderNode>): List<TextRun> =
        nodes.filterIsInstance<TextNode>().flatMap { it.runs }

    private fun visibleText(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) n.runs.forEach { if (it.audioTrack == null) append(it.s) }
    }

    @Test
    fun `a question marker becomes an audio run carrying its track index`() {
        val out = compileHtml("он [anki:play:q:0]", side = "front")
        val audioRuns = runsOf(out).filter { it.audioTrack != null }
        assertEquals(listOf(0), audioRuns.map { it.audioTrack })
        // The word survives; the marker does not leak as literal text.
        assertEquals("он ", visibleText(out))
        assertTrue(runsOf(out).none { "anki:play" in it.s }, "marker must not leak: ${runsOf(out)}")
    }

    @Test
    fun `an answer marker is recognized too`() {
        val out = compileHtml("он [anki:play:a:0]", side = "back")
        assertEquals(listOf(0), runsOf(out).filter { it.audioTrack != null }.map { it.audioTrack })
    }

    @Test
    fun `the audio run sits at the marker position after the word it follows`() {
        val out = compileHtml("word [anki:play:q:0] rest", side = "front")
        val runs = runsOf(out)
        val audioIdx = runs.indexOfFirst { it.audioTrack != null }
        assertTrue(audioIdx >= 0, "expected an audio run")
        // The run BEFORE the audio ends with the word; the run AFTER continues the text.
        assertTrue(runs[audioIdx - 1].s.endsWith("word "), "audio must follow 'word ', got ${runs[audioIdx - 1].s}")
        assertTrue(runs.drop(audioIdx + 1).any { it.s.contains("rest") }, "text after marker must survive")
    }

    @Test
    fun `multiple markers keep their distinct track indices in order`() {
        val out = compileHtml("word [anki:play:q:0] then sentence [anki:play:q:1] end", side = "front")
        val tracks = runsOf(out).filter { it.audioTrack != null }.map { it.audioTrack }
        assertEquals(listOf(0, 1), tracks)
    }

    @Test
    fun `a marker split across block elements keeps each in its own block`() {
        // word audio after the word, sentence audio after the sentence — two blocks.
        val html = """<div>Слово [anki:play:a:0]</div><div>Предложение [anki:play:a:1]</div>"""
        val out = compileHtml(html, side = "back")
        val blocks = out.filterIsInstance<TextNode>()
        assertEquals(2, blocks.size, "expected two text blocks, got $out")
        assertEquals(listOf(0), blocks[0].runs.mapNotNull { it.audioTrack })
        assertEquals(listOf(1), blocks[1].runs.mapNotNull { it.audioTrack })
    }

    @Test
    fun `an audio-only marker still yields a positioned audio run`() {
        // Defensive: a marker with no surrounding visible text is still positioned
        // (a TextNode with a single audio run), never dropped.
        val out = compileHtml("[anki:play:q:2]", side = "front")
        assertEquals(listOf(2), runsOf(out).filter { it.audioTrack != null }.map { it.audioTrack })
    }

    @Test
    fun `an audio marker inside a flex-row header cell survives into that cell`() {
        // RC5000 shape: the word audio sits in the center cell of the flex header, NOT at the
        // top level. The marker must land as an audio run inside that RowNode cell (else the
        // engine's positioned-track scan would miss it and wrongly keep the aggregate row).
        val css = ".row { display: flex }"
        val html = """<div class="row"><div>L</div><div>он [anki:play:a:0]</div><div>R</div></div>"""
        val out = compileHtml(html, side = "back", css = css)
        val row = out.filterIsInstance<RowNode>().single()
        val cellRuns = row.cells.flatMap { it.nodes }.filterIsInstance<TextNode>().flatMap { it.runs }
        assertEquals(listOf(0), cellRuns.mapNotNull { it.audioTrack }, "word audio must live inside the cell")
    }

    @Test
    fun `ordinary double-bracket text without the anki-play shape is untouched`() {
        val out = compileHtml("see [not:a:marker] here", side = "front")
        assertTrue(runsOf(out).none { it.audioTrack != null }, "must not treat arbitrary [x:y] as audio")
        assertEquals("see [not:a:marker] here", visibleText(out).trim())
    }
}
