package com.dvdutch.recall.engine

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.compiler.Element
import com.dvdutch.recall.compiler.HtmlTokenizer

/**
 * Parses rslib's `compareAnswer` HTML into a single [TextNode] whose runs carry
 * monochrome-safe styling that preserves the right/wrong/missed classification.
 *
 * `compareAnswer` returns one `<code id=typeans>` wrapping a sequence of
 * classed spans, `<br>` breaks and a `<span id=typearrow>&darr;</span>` separator:
 *   `<span class=typeGood>` correctly typed chars,
 *   `<span class=typeBad>`  wrong chars the user typed,
 *   `<span class=typeMissed>` expected chars the user omitted.
 *
 * We deliberately DO NOT route this through the parity HTML→node compiler: that
 * flattens every span to plain mono, losing the class information the diff exists to
 * convey. Instead this dedicated parser maps each class to run flags:
 *   - typeGood   -> mono
 *   - typeBad    -> mono + strike   (a wrong char, struck through)
 *   - typeMissed -> mono + underline (a missed char, underlined)
 * `<br>` becomes a newline; the arrow entity is decoded (via the shared tokenizer).
 *
 * Pure and Compose-free, so the whole mapping is unit-testable on the JVM.
 */
fun parseTypeAnswerDiff(html: String): RenderNode {
    // `&darr;` is the compareAnswer arrow separator, but it is NOT in the shared
    // tokenizer's (parity-governed, card-template-scoped) entity table — so decode it
    // here, before tokenizing, rather than widening that table. This is the only extra
    // entity compareAnswer emits beyond the common set the tokenizer already handles.
    val root = HtmlTokenizer.parse(html.replace("&darr;", "↓"))
    val runs = mutableListOf<TextRun>()
    walkDiff(root, runs)
    return TextNode(runs)
}

private fun walkDiff(el: Element, out: MutableList<TextRun>) {
    val tag = el.tag
    if (tag == "br") {
        out.add(TextRun("\n", mono = true))
        el.tail?.takeIf { it.isNotEmpty() }?.let { out.add(TextRun(it, mono = true)) }
        return
    }

    // A classed span is a leaf diff segment: emit one styled run for its text.
    val cls = el.attr("class")
    if (tag == "span" && cls != null) {
        val text = el.textContent()
        if (text.isNotEmpty()) {
            out.add(
                TextRun(
                    s = text,
                    mono = true,
                    strike = cls == "typeBad",
                    underline = cls == "typeMissed",
                    // typeGood (and any unknown class) -> plain mono.
                ),
            )
        }
        el.tail?.takeIf { it.isNotEmpty() }?.let { out.add(TextRun(it, mono = true)) }
        return
    }

    // The typearrow span (id=typearrow, no class) and any wrapper (code / root):
    // descend, emitting its own direct text and each child in order.
    el.text?.takeIf { it.isNotEmpty() }?.let { out.add(TextRun(it, mono = true)) }
    for (child in el.children) {
        walkDiff(child, out)
    }
    el.tail?.takeIf { it.isNotEmpty() }?.let { out.add(TextRun(it, mono = true)) }
}
