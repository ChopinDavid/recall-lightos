package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun

/**
 * Lays [compileHtml]'s nodes out as lines, the way a browser would. Phone-side only:
 * [compileHtml] (and its parity with the Python reference) is untouched.
 *
 *  - An inline cloze and the text around it ([TextNode.joinsPrevious] /
 *    [ClozeNode.joinsPrevious]) become ONE [TextNode], the cloze as a bold run — so
 *    "{{c1::Metformin}} is first-line…" reads on one line instead of three.
 *  - Whitespace at the start or end of a line, around a `<br>`, or doubled where two
 *    runs meet is dropped (HTML collapses it): an answer after `<hr id=answer>` no
 *    longer starts with a space.
 *
 * Applied to each card side before display, so the question and the answer's
 * `{{FrontSide}}` prefix lay out identically and node counts stay comparable.
 */
object InlineFlow {

    fun apply(nodes: List<RenderNode>): List<RenderNode> {
        val out = mutableListOf<RenderNode>()
        val line = mutableListOf<RenderNode>()
        fun flushLine() {
            if (line.isNotEmpty()) out.add(joinLine(line))
            line.clear()
        }
        for (node in nodes) {
            when (node) {
                is TextNode, is ClozeNode -> {
                    if (line.isNotEmpty() && !node.joins()) flushLine()
                    // `<img><br>text` is how Anki's editor writes an image followed by text.
                    // In a browser the <br> only ends the image's line; since the image is
                    // already its own block here, the break would add a blank line.
                    line.add(if (out.lastOrNull() is ImageNode && line.isEmpty()) node.withoutLeadingBreak() else node)
                }
                is RowNode -> {
                    flushLine()
                    out.add(node.copy(cells = node.cells.map { it.copy(nodes = apply(it.nodes)) }))
                }
                else -> {
                    flushLine()
                    out.add(node)
                }
            }
        }
        flushLine()
        return out
    }

    private fun RenderNode.withoutLeadingBreak(): RenderNode {
        val text = this as? TextNode ?: return this
        val first = text.runs.firstOrNull() ?: return this
        if (first.audioTrack != null || first.pre || !first.s.startsWith("\n")) return this
        val rest = first.s.removePrefix("\n")
        val runs = if (rest.isEmpty()) text.runs.drop(1) else listOf(first.copy(s = rest)) + text.runs.drop(1)
        return text.copy(runs = runs)
    }

    private fun RenderNode.joins(): Boolean = when (this) {
        is TextNode -> joinsPrevious
        is ClozeNode -> joinsPrevious
        else -> false
    }

    /** One line: the first text node's layout (align, margins, scale) with every piece's runs. */
    private fun joinLine(line: List<RenderNode>): TextNode {
        val runs = mutableListOf<TextRun>()
        for (node in line) when (node) {
            is TextNode -> runs.addAll(node.runs)
            is ClozeNode -> {
                if (node.spaceBefore) runs.add(TextRun(" "))
                runs.addAll(node.runs?.map { if (it.audioTrack != null) it else it.copy(b = true) } ?: clozeRuns(node))
            }
            else -> {}
        }
        val layout = line.firstOrNull { it is TextNode } as TextNode?
        return TextNode(
            runs = trimWhitespace(runs),
            align = layout?.align ?: BlockAlign.START,
            marginTop = layout?.marginTop ?: 0f,
            marginBottom = layout?.marginBottom ?: 0f,
            scale = layout?.scale ?: 1f,
        )
    }

    /**
     * A cloze as bold runs. A revealed answer can contain a sound (`[anki:play:a:N]`,
     * flattened into the cloze text); it becomes an inline audio run — the tappable
     * replay glyph — instead of showing the marker as text.
     */
    private fun clozeRuns(node: ClozeNode): List<TextRun> {
        val label = clozeLabel(node)
        if (!label.contains("[anki:play:")) return listOf(TextRun(label, b = true))
        val out = mutableListOf<TextRun>()
        var last = 0
        for (m in AUDIO_MARKER.findAll(label)) {
            if (m.range.first > last) out.add(TextRun(label.substring(last, m.range.first), b = true))
            out.add(TextRun("", audioTrack = m.groupValues[1].toInt()))
            last = m.range.last + 1
        }
        if (last < label.length) out.add(TextRun(label.substring(last), b = true))
        return out
    }

    private val AUDIO_MARKER = Regex("""\[anki:play:[qa]:(\d+)]""")

    /** A hidden cloze shows its hint (or `...`) in brackets; a revealed one, its answer. */
    internal fun clozeLabel(node: ClozeNode): String =
        if (node.state == "hidden") "[" + (node.hint?.takeIf { it.isNotBlank() } ?: "...") + "]"
        else node.text.orEmpty().trim()

    /**
     * Browser whitespace across a line's runs: no spaces at the start or end of the
     * line or next to a line break, and never two in a row (runs each keep their own
     * edge spaces, so "a " + " b" would otherwise double up). Audio runs count as
     * content. Runs left empty are dropped.
     */
    internal fun trimWhitespace(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        // What the line ends with so far: start-of-line, a space, or other content.
        var atLineStart = true
        var lastWasSpace = false
        for (run in runs) {
            if (run.audioTrack != null) {
                out.add(run)
                atLineStart = false
                lastWasSpace = false
                continue
            }
            if (run.pre) {
                // Preformatted: verbatim, and the line state follows how it ends.
                if (run.s.isEmpty()) continue
                out.add(run)
                atLineStart = run.s.endsWith('\n')
                lastWasSpace = false
                continue
            }
            val sb = StringBuilder()
            for (c in run.s) {
                when (c) {
                    ' ' -> if (!atLineStart && !lastWasSpace) {
                        sb.append(c)
                        lastWasSpace = true
                    }
                    '\n' -> {
                        // A space right before a line break is dropped too.
                        if (lastWasSpace) dropTrailingSpace(sb, out)
                        sb.append(c)
                        atLineStart = true
                        lastWasSpace = false
                    }
                    else -> {
                        sb.append(c)
                        atLineStart = false
                        lastWasSpace = false
                    }
                }
            }
            if (sb.isNotEmpty()) out.add(run.copy(s = sb.toString()))
        }
        if (lastWasSpace) dropTrailingSpace(null, out)
        // A single <br> at the very end of a block is invisible in a browser (a Cloze
        // back's `<br>{{Back Extra}}` with an empty extra); two still show a blank line.
        val last = out.indexOfLast { it.audioTrack == null }
        if (last >= 0 && out[last].s.endsWith("\n")) {
            val trimmed = out[last].s.dropLast(1)
            if (trimmed.isEmpty()) out.removeAt(last) else out[last] = out[last].copy(s = trimmed)
        }
        return out
    }

    /** Removes the single trailing space, from [sb] if it ends there, else from [out]'s last text run. */
    private fun dropTrailingSpace(sb: StringBuilder?, out: MutableList<TextRun>) {
        if (sb != null && sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
            return
        }
        val i = out.indexOfLast { it.audioTrack == null && it.s.isNotEmpty() }
        if (i < 0 || !out[i].s.endsWith(' ')) return
        val trimmed = out[i].s.dropLast(1)
        if (trimmed.isEmpty()) out.removeAt(i) else out[i] = out[i].copy(s = trimmed)
    }
}
