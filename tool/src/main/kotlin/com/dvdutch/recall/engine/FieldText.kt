package com.dvdutch.recall.engine

import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.compiler.compileHtml

/**
 * Reduces a note field's stored HTML to the plain text a type-answer comparison
 * expects: HTML tags removed, entities decoded, `[sound:]`/`[anki:...]` media refs
 * dropped, images dropped, and whitespace collapsed/trimmed.
 *
 * Anki compares the typed answer against the answer field with markup and media
 * stripped (desktop's `strip_html` + av-ref removal). We reuse the existing, well-
 * tested HTML→node [compileHtml] path for tag/entity/whitespace handling — joining
 * its text runs yields exactly that plain text — after first removing the bracketed
 * media references [compileHtml] never sees on-device (the backend's `stripAvTags`
 * normally does that upstream, but this helper is pure so it does it itself).
 *
 * Pure and Compose/backend-free, so the whole mapping is unit-testable on the JVM.
 */
fun fieldTextForCompare(fieldHtml: String): String {
    // Drop `[sound:...]` and `[anki:...]` bracket refs before compiling — they are
    // media/playback markers, never part of the expected answer text.
    val noMedia = BRACKET_MEDIA.replace(fieldHtml, "")
    // Like Anki's html_to_text_line: separate blocks and <br> line breaks become a
    // single space, so `<div>New</div><div>York</div>` and `New<br>York` both expect
    // "New York" (joining blocks with nothing gave "NewYork").
    return compileHtml(noMedia, side = "front")
        .filterIsInstance<TextNode>()
        .joinToString(" ") { node -> node.runs.joinToString("") { it.s } }
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

private val WHITESPACE = Regex("""\s+""")

// `[sound:x.mp3]` and `[anki:play:...]`-style refs; non-greedy up to the first ].
private val BRACKET_MEDIA = Regex("""\[(?:sound|anki):[^]]*]""")
