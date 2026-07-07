package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RowCell
import com.dvdutch.recall.api.RowNode
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
fun compileHtml(
    html: String,
    side: String,
    css: String = "",
    tags: List<String> = emptyList(),
): List<RenderNode> {
    if (html.isBlank()) return emptyList()
    val hidden = CssHidden.parse(css)
    val layout = CssTextAlign.parse(css)
    val margins = CssMargins.parse(css)
    val fontSize = CssFontSize.parse(css)
    // The card base px: the font-size on the card root class (e.g. prettify-flashcard's
    // 28px), else the default. Every per-node scale is measured against this one base.
    val cardBasePx = fontSize.cardBasePx(CARD_ROOT_CLASSES)
    // The tokenizer never raises on any input; libxml2's `fragment_fromstring`
    // ParserError/ValueError fallback (flatten to a single text node) is
    // therefore unreachable in practice, but we keep the never-empty contract:
    // an empty parse simply yields no nodes.
    val root = HtmlTokenizer.parse(html)
    // Only tags that actually carry a `::` can produce a leaf, so pre-filter to
    // the hierarchical ones. Empty (the common case) disables the mapping wholly.
    val ctx = Ctx(side, tags.filter { it.contains("::") }.toSet())
    walk(root, ctx, emptySet(), hidden, layout, margins, fontSize, cardBasePx, BlockAlign.START, emptyList())
    ctx.flushText()
    return ctx.nodes
}

// The card root class whose declared font-size (if any) sets the base every scale is
// measured against. Matches the anki-prettify wrapper; absent → CssFontSize default.
private val CARD_ROOT_CLASSES = listOf("prettify-flashcard", "card")

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

// A styled text run, OR (when [audioTrack] != null) an inline audio marker run: empty
// visible text, carrying the 0-based index into the side's audio list. Audio runs never
// merge with text runs (they carry a non-null token), so they keep their exact position.
private class Run(val s: String, val styles: Set<String>, val audioTrack: Int? = null)

/**
 * Matches the backend's inline play markers `[anki:play:q:N]` / `[anki:play:a:N]`
 * (from `extractAvTags(...).text`), capturing the 0-based track index N. Only this
 * exact shape is treated as audio; any other `[x:y:z]` bracket text is left intact.
 */
private val AUDIO_MARKER = Regex("""\[anki:play:[qa]:(\d+)]""")

/**
 * Splits [text] into alternating visible-text and inline-audio [Run]s at each
 * `[anki:play:q|a:N]` marker, preserving the surrounding text byte-for-byte. A text
 * without any marker yields a single text [Run] (the common, hot path). Each marker
 * becomes an audio [Run] carrying its captured track index; the marker itself never
 * survives as literal text.
 */
private fun splitAudioMarkers(text: String, styles: Set<String>): List<Run> {
    if (!text.contains("[anki:play:")) return listOf(Run(text, styles))
    val out = mutableListOf<Run>()
    var last = 0
    for (m in AUDIO_MARKER.findAll(text)) {
        if (m.range.first > last) out.add(Run(text.substring(last, m.range.first), styles))
        out.add(Run("", styles, audioTrack = m.groupValues[1].toInt()))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(Run(text.substring(last), styles))
    return out
}

private class Ctx(val side: String, val hierarchicalTags: Set<String> = emptySet()) {
    val nodes = mutableListOf<RenderNode>()
    private val runs = mutableListOf<Run>()

    // The block alignment in effect for text flushed right now (Feature 2). Set
    // by [walk] on entering an aligned block and restored on leaving, so a flush
    // stamps the alignment of the block that produced the run.
    var align: BlockAlign = BlockAlign.START

    // Fix D: the vertical margins (em) in effect for text flushed right now, stamped
    // from the block element that produced the run; reset with [align].
    var marginTop: Float = 0f
    var marginBottom: Float = 0f

    // Task 1: the font scale in effect for text flushed right now (nearest declared
    // ancestor size / card base, clamped); reset with [align].
    var scale: Float = 1f

    fun flushText() {
        // Audio marker runs are empty-text but must survive (they position an inline replay
        // glyph), so a block is emitted when it has any non-blank text OR any audio run.
        val kept = runs.filter { it.s.isNotEmpty() || it.audioTrack != null }
        if (kept.isNotEmpty() && kept.any { it.s.isNotBlank() || it.audioTrack != null }) {
            nodes.add(TextNode(mergeRuns(kept, hierarchicalTags), align, marginTop, marginBottom, scale))
        }
        runs.clear()
    }

    fun addRun(text: String, styles: Set<String>) {
        if (text.isEmpty()) return
        // Split out any inline `[anki:play:q|a:N]` markers into audio runs at their position;
        // marker-free text (the common case) stays one run.
        runs.addAll(splitAudioMarkers(text, styles))
    }
}

/**
 * Rewrites whitespace-delimited tokens that EXACTLY equal one of the note's
 * hierarchical [tags] (an `a::b::c` tag) to their leaf segment (`c`), preserving
 * the original whitespace between tokens. Exact-match only: prose or cloze text
 * that merely contains `::` (and isn't a note tag) is left byte-for-byte intact.
 * Matches AnkiDroid's prettify-tags `<script>`, which shows the leaf of `{{Tags}}`.
 */
private fun mapTagLeaves(s: String, tags: Set<String>): String {
    if (tags.isEmpty() || !s.contains("::")) return s
    val sb = StringBuilder()
    val token = StringBuilder()
    fun flushToken() {
        if (token.isEmpty()) return
        val t = token.toString()
        sb.append(if (t in tags) t.substringAfterLast("::") else t)
        token.setLength(0)
    }
    for (c in s) {
        if (c.isPyWhitespace()) {
            flushToken()
            sb.append(c)
        } else {
            token.append(c)
        }
    }
    flushToken()
    return sb.toString()
}

private fun mergeRuns(runs: List<Run>, tags: Set<String> = emptySet()): List<TextRun> {
    val merged = mutableListOf<Run>()
    for (r in runs) {
        val last = merged.lastOrNull()
        // Never merge into/across an audio marker run — it must keep its exact position and
        // its distinct track index. Only merge two plain text runs with identical styles.
        if (last != null && last.audioTrack == null && r.audioTrack == null && last.styles == r.styles) {
            merged[merged.size - 1] = Run(last.s + r.s, last.styles)
        } else {
            merged.add(r)
        }
    }
    return merged.map { run ->
        if (run.audioTrack != null) return@map TextRun(s = "", audioTrack = run.audioTrack)
        TextRun(
            s = mapTagLeaves(run.s, tags),
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

private fun walk(
    el: Element,
    ctx: Ctx,
    styles: Set<String>,
    hidden: CssHidden,
    layout: CssTextAlign,
    margins: CssMargins,
    fontSize: CssFontSize,
    cardBasePx: Float,
    inheritedAlign: BlockAlign,
    // Ancestor class list, NEAREST-first, of the enclosing blocks that have already
    // been entered — so this element's own classes are prepended before resolving the
    // nearest-ancestor font size.
    ancestorClasses: List<String>,
) {
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

    // Fix D: this element's own vertical margins (em) from its class CSS.
    val marginTop = margins.topEmForClasses(classes)
    val marginBottom = margins.bottomEmForClasses(classes)

    // Task 1: the class chain seen from THIS element outward (nearest-first), and the
    // resulting font scale (nearest ancestor with a declared size wins).
    val chainClasses = classes + ancestorClasses
    val scale = fontSize.scaleForClasses(chainClasses, cardBasePx)

    // Feature 2 / Fix B: this element's effective PLACEMENT alignment. An
    // inline-block's own text-align governs only its internal content, NOT its
    // placement — placement follows the parent (CSS shrink-to-fit + inline
    // centering). So for an inline-block we ignore its own text-align and keep the
    // inherited placement; otherwise the element's own text-align (inline style
    // wins over class CSS) applies, else inherit the enclosing block's alignment.
    val elAlign = if (isInlineBlock(el, layout, classes)) {
        inheritedAlign
    } else {
        inlineTextAlign(el) ?: layout.alignForClasses(classes) ?: inheritedAlign
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
        ctx.nodes.add(RuleNode(marginTop = marginTop, marginBottom = marginBottom))
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

    // Feature 1: a flex-row element compiles its ELEMENT children as row cells,
    // under strict guardrails, else falls through to vertical linearization.
    if (layout.isFlexRow(classes) &&
        tryCompileFlexRow(
            el, ctx, hidden, layout, margins, fontSize, cardBasePx,
            elAlign, marginTop, marginBottom, chainClasses,
        )
    ) {
        el.tail?.let { ctx.addRun(cleanWs(it), styles) }
        return
    }

    val childStyles = STYLE_TAGS[tag]?.let { styles + it } ?: styles
    val isBlock = tag in BLOCK
    if (isBlock) {
        ctx.flushText()
        ctx.align = elAlign
        ctx.marginTop = marginTop
        ctx.marginBottom = marginBottom
        ctx.scale = scale
        if (tag == "li") ctx.addRun("• ", emptySet())
    }

    el.text?.let { ctx.addRun(cleanWs(it), childStyles) }
    for (child in el.children) {
        walk(child, ctx, childStyles, hidden, layout, margins, fontSize, cardBasePx, elAlign, chainClasses)
    }

    if (isBlock) {
        ctx.flushText()
        ctx.align = inheritedAlign
        ctx.marginTop = 0f
        ctx.marginBottom = 0f
        ctx.scale = 1f
    }
    el.tail?.let { ctx.addRun(cleanWs(it), styles) }
}

/**
 * Compile [el]'s ELEMENT children as cells of a [RowNode], appending it to
 * [ctx]. Returns false (leaving [ctx] untouched) when the bounded guardrails
 * reject the element, so the caller falls back to vertical linearization:
 *   - 2–4 element children only;
 *   - no non-whitespace loose text (element `text` or child `tail`).
 * `flex-direction: column` is already excluded upstream by [CssTextAlign].
 *
 * Cell roles by position: first START/wrap, last END/wrap, middle(s) CENTER/
 * weight 1. Each cell compiles in its own sub-context (so a divider `hr` becomes
 * a [RuleNode] spanning that cell), inheriting [rowAlign] as its text-align base.
 */
private fun tryCompileFlexRow(
    el: Element,
    ctx: Ctx,
    hidden: CssHidden,
    layout: CssTextAlign,
    margins: CssMargins,
    fontSize: CssFontSize,
    cardBasePx: Float,
    rowAlign: BlockAlign,
    rowMarginTop: Float,
    rowMarginBottom: Float,
    rowChainClasses: List<String>,
): Boolean {
    val kids = el.children.filter { it.tag != null }
    if (kids.size < 2 || kids.size > 4) return false
    // Reject any non-whitespace loose text: the element's own text, or the tail
    // that trails each child before the next sibling.
    if (!pyStrip(el.text ?: "").isEmpty()) return false
    if (el.children.any { !pyStrip(it.tail ?: "").isEmpty() }) return false

    val last = kids.size - 1
    // Fix A: when EVERY child carries an explicit bare `flex: <n>` (via its own
    // class CSS), weight each cell by that number (flex-basis-0 semantics) so a
    // `.25 / 1 / .25` header lays out with equal corners and a truly-centred
    // middle. Otherwise fall back to the positional default (corners wrap-content,
    // middle weight 1).
    val childFlex = kids.map { layout.flexForClasses(pySplit(it.attr("class") ?: "")) }
    val allExplicit = childFlex.all { it != null }
    val cells = kids.mapIndexed { i, child ->
        // The cell's own text-align (its class CSS) wins; else the positional
        // default (first START, last END, middle CENTER).
        val classes = pySplit(child.attr("class") ?: "")
        val align = layout.alignForClasses(classes) ?: when (i) {
            0 -> BlockAlign.START
            last -> BlockAlign.END
            else -> BlockAlign.CENTER
        }
        val weight = if (allExplicit) childFlex[i] else if (i == 0 || i == last) null else 1f
        val cellCtx = Ctx(ctx.side, ctx.hierarchicalTags)
        cellCtx.align = align
        walk(child, cellCtx, emptySet(), hidden, layout, margins, fontSize, cardBasePx, align, rowChainClasses)
        cellCtx.flushText()
        RowCell(nodes = cellCtx.nodes.toList(), weight = weight, align = align)
    }
    ctx.flushText()
    ctx.nodes.add(RowNode(cells, marginTop = rowMarginTop, marginBottom = rowMarginBottom))
    return true
}

private fun isInlineHidden(el: Element): Boolean =
    CssHidden.declaresHidden(el.attr("style") ?: "")

/**
 * True iff [el] is `display: inline-block` via its class CSS or its inline
 * `style="display: inline-block"`. Such an element's own text-align governs only
 * its internal content; its placement follows the parent (Fix B).
 */
private fun isInlineBlock(el: Element, layout: CssTextAlign, classes: List<String>): Boolean {
    if (layout.isInlineBlock(classes)) return true
    val style = el.attr("style") ?: return false
    if (!style.contains("display", ignoreCase = true)) return false
    for (decl in style.split(";")) {
        val idx = decl.indexOf(':')
        if (idx < 0) continue
        if (decl.substring(0, idx).trim().lowercase() != "display") continue
        if (decl.substring(idx + 1).trim().lowercase() == "inline-block") return true
    }
    return false
}

/** The `text-align` from an element's inline `style="…"`, mapped to [BlockAlign], or null. */
private fun inlineTextAlign(el: Element): BlockAlign? {
    val style = el.attr("style") ?: return null
    if (!style.contains("text-align", ignoreCase = true)) return null
    var result: BlockAlign? = null
    for (decl in style.split(";")) {
        val idx = decl.indexOf(':')
        if (idx < 0) continue
        if (decl.substring(0, idx).trim().lowercase() != "text-align") continue
        when (decl.substring(idx + 1).trim().lowercase()) {
            "left", "start", "justify" -> result = BlockAlign.START
            "center" -> result = BlockAlign.CENTER
            "right", "end" -> result = BlockAlign.END
        }
    }
    return result
}

// ASCII-only digit check for img width/height, INTENTIONALLY narrower than
// Python's str.isdigit(). str.isdigit() also accepts Unicode digits (e.g.
// superscript "²"), which the reference then feeds to int() — and int("²")
// RAISES ValueError, violating the never-raises contract. ASCII-only is the
// safe, superset-compatible choice: it accepts every value int() would accept
// without ever admitting one int() would reject. lxml attribute values here
// only carry ASCII digits when genuinely numeric, so parity is unaffected.
private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
