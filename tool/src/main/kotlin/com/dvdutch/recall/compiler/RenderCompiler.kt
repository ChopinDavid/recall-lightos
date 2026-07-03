package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.UnsupportedNode

/**
 * HTML card side -> render nodes. Direct port of `anki_bridge.compiler`.
 *
 * Degradation ladder, per element:
 *   1. known tag       -> mapped node / style run
 *   2. unknown textual -> descend into children (keeps the text)
 *   3. unrenderable    -> UnsupportedNode(kind = tag)
 * Never raises on any input; worst case is a text node of the flattened content.
 *
 * `ImageNode.src` carries the bare, percent-DECODED media filename (the value of
 * the `src` attribute as authored) — NOT a `/v1/media/...` URL. This is the new
 * phone wire contract.
 */
fun compileHtml(html: String, side: String, css: String = ""): List<RenderNode> {
    if (html.isBlank()) return emptyList()
    val hidden = CssHidden.parse(css)
    // The tokenizer never raises on any input; libxml2's `fragment_fromstring`
    // ParserError/ValueError fallback (flatten to a single text node) is
    // therefore unreachable in practice, but we keep the never-empty contract:
    // an empty parse simply yields no nodes.
    val root = HtmlTokenizer.parse(html)
    val ctx = Ctx(side)
    walk(root, ctx, emptySet(), hidden)
    ctx.flushText()
    return ctx.nodes
}

// tags whose content cannot be meaningfully flattened to text
private val UNSUPPORTED = setOf("video", "audio", "object", "embed", "iframe", "canvas", "svg", "applet")

// tags dropped entirely, including content
private val DROPPED = setOf("script", "style", "head", "title")

// block-level tags that force a new text node
private val BLOCK = setOf("div", "p", "li", "ul", "ol", "table", "tr", "blockquote", "section", "article", "center")

private val STYLE_TAGS = mapOf(
    "b" to "b", "strong" to "b",
    "i" to "i", "em" to "i",
    "code" to "mono", "pre" to "mono", "tt" to "mono", "kbd" to "mono",
    "s" to "strike", "del" to "strike", "strike" to "strike",
    "small" to "small", "sub" to "small", "sup" to "small",
)

private class Run(val s: String, val styles: Set<String>)

private class Ctx(val side: String) {
    val nodes = mutableListOf<RenderNode>()
    private val runs = mutableListOf<Run>()

    fun flushText() {
        val nonEmpty = runs.filter { it.s.isNotEmpty() }
        if (nonEmpty.isNotEmpty() && nonEmpty.any { it.s.isNotBlank() }) {
            nodes.add(TextNode(mergeRuns(nonEmpty)))
        }
        runs.clear()
    }

    fun addRun(text: String, styles: Set<String>) {
        if (text.isEmpty()) return
        runs.add(Run(text, styles))
    }
}

private fun mergeRuns(runs: List<Run>): List<TextRun> {
    val merged = mutableListOf<Run>()
    for (r in runs) {
        val last = merged.lastOrNull()
        if (last != null && last.styles == r.styles) {
            merged[merged.size - 1] = Run(last.s + r.s, last.styles)
        } else {
            merged.add(r)
        }
    }
    return merged.map { run ->
        TextRun(
            s = run.s,
            b = "b" in run.styles,
            i = "i" in run.styles,
            small = "small" in run.styles,
            mono = "mono" in run.styles,
            strike = "strike" in run.styles,
        )
    }
}

/**
 * Collapse HTML whitespace like a browser: internal runs -> single space, and a
 * leading/trailing space is preserved (it separates adjacent inline runs).
 * All-whitespace -> single space.
 *
 * Ported from Python `_clean_ws`, which relies on `str.split()` / `str.strip()`
 * / `str[i].isspace()`. Those use Python's `str.isspace()` whitespace set (which
 * INCLUDES NBSP ` `, ``, and the Unicode space separators) — a
 * strictly wider set than Java/Kotlin's regex `\s` or `Char.isWhitespace()`. To
 * stay byte-identical we split/strip on exactly [isPyWhitespace].
 */
private fun cleanWs(rawText: String?): String {
    if (rawText.isNullOrEmpty()) return ""
    if (pyStrip(rawText).isEmpty()) return " "
    // Math delimiter regions (`\(...\)`, `\[...\]`, `[$]...[$]`, `[$$]...[$$]`)
    // are rewritten to a Unicode subset HERE — before whitespace collapse — so
    // the delimiters and their contents are handled as one text piece, before
    // the content is split into styled runs. Non-math text is untouched.
    val text = MathUnicode.transform(rawText)
    if (text.isEmpty()) return ""
    val collapsed = pySplit(text).joinToString(" ")
    val sb = StringBuilder()
    if (text[0].isPyWhitespace()) sb.append(' ')
    sb.append(collapsed)
    if (text[text.length - 1].isPyWhitespace()) sb.append(' ')
    return sb.toString()
}

/**
 * Python's `str.isspace()` whitespace set, matched exactly. Java's
 * `Character.isWhitespace` disagrees on NBSP (` `, ` `, ` ` are
 * non-breaking and NOT Java-whitespace) and on ``, so we enumerate the
 * Python set directly. (Source: CPython unicodetype — Py_UNICODE_ISSPACE.)
 */
internal fun Char.isPyWhitespace(): Boolean = when (this.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0x85, 0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    -> true
    else -> false
}

/** Equivalent of Python `str.split()` with no args: split on runs of whitespace, no empties. */
internal fun pySplit(s: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    for (c in s) {
        if (c.isPyWhitespace()) {
            if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        } else {
            sb.append(c)
        }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

/** Equivalent of Python `str.strip()` with no args. */
private fun pyStrip(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].isPyWhitespace()) start++
    while (end > start && s[end - 1].isPyWhitespace()) end--
    return s.substring(start, end)
}

private fun walk(el: Element, ctx: Ctx, styles: Set<String>, hidden: CssHidden) {
    val tag = el.tag

    if (tag in DROPPED) return

    if (tag == null) {
        // comment / PI: drop body, keep tail.
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    val classes = pySplit(el.attr("class") ?: "")
    val elId = el.attr("id") ?: ""
    if (hidden.matches(tag, classes, elId) || isInlineHidden(el)) {
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    if (tag in UNSUPPORTED) {
        ctx.flushText()
        ctx.nodes.add(UnsupportedNode(tag))
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    if (tag == "img") {
        ctx.flushText()
        val w = el.attr("width")?.takeIf { it.isAsciiDigits() }?.toInt()
        val h = el.attr("height")?.takeIf { it.isAsciiDigits() }?.toInt()
        // Bare, percent-decoded filename: the src attribute value as authored.
        ctx.nodes.add(ImageNode(src = el.attr("src") ?: "", w = w, h = h))
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    if (tag == "hr") {
        ctx.flushText()
        ctx.nodes.add(RuleNode)
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    if (tag == "br") {
        ctx.addRun("\n", emptySet())
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    if (tag == "span" && "cloze" in classes) {
        ctx.flushText()
        val node: ClozeNode = if (ctx.side == "front") {
            val hint = el.text ?: ""
            if (hint != "[...]" && hint != "[…]" && hint.isNotEmpty() &&
                hint.startsWith("[") && hint.endsWith("]")
            ) {
                ClozeNode(state = "hidden", hint = hint.substring(1, hint.length - 1))
            } else {
                ClozeNode(state = "hidden")
            }
        } else {
            ClozeNode(state = "revealed", text = cleanWs(el.textContent()))
        }
        ctx.nodes.add(node)
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    val childStyles = STYLE_TAGS[tag]?.let { styles + it } ?: styles
    val isBlock = tag in BLOCK
    if (isBlock) {
        ctx.flushText()
        if (tag == "li") ctx.addRun("• ", emptySet())
    }

    el.text?.let { ctx.addRun(cleanWs(it), childStyles) }
    for (child in el.children) {
        walk(child, ctx, childStyles, hidden)
    }

    if (isBlock) ctx.flushText()
    el.tail?.let { ctx.addRun(cleanWs(it), styles) }
}

private fun isInlineHidden(el: Element): Boolean =
    CssHidden.declaresHidden(el.attr("style") ?: "")

// ASCII-only digit check for img width/height, INTENTIONALLY narrower than
// Python's str.isdigit(). str.isdigit() also accepts Unicode digits (e.g.
// superscript "²"), which the reference then feeds to int() — and int("²")
// RAISES ValueError, violating the never-raises contract. ASCII-only is the
// safe, superset-compatible choice: it accepts every value int() would accept
// without ever admitting one int() would reject. lxml attribute values here
// only carry ASCII digits when genuinely numeric, so parity is unaffected.
private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
