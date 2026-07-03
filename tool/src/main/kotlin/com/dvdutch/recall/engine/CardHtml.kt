package com.dvdutch.recall.engine

import anki.card_rendering.RenderedTemplateNode

/**
 * Assembles a rendered card side (a list of [RenderedTemplateNode]) into the final
 * HTML string, mirroring AnkiDroid's `TemplateManager.applyCustomFilters`
 * (libanki/src/main/java/com/ichi2/anki/libanki/TemplateManager.kt, GPL-3 —
 * reimplemented, not copied).
 *
 * rslib returns each side as a sequence of nodes:
 *   - a **text** node: literal template text, appended verbatim;
 *   - a **replacement** node: a `{{field}}` (or filtered `{{filter:field}}`) whose
 *     value rslib has already resolved into `currentText`. AnkiDroid then runs any
 *     app-registered custom field filters over that text; we register none, so —
 *     exactly as AnkiDroid does when a filter is unknown — the resolved
 *     `currentText` is appended unchanged (unresolved custom filters therefore
 *     surface as their already-substituted field text, never as a crash).
 *
 * The `FrontSide` special-case in the reference does not apply here:
 * `renderExistingCard` resolves `{{FrontSide}}` in the answer side itself, so
 * `currentText` already carries the front HTML by the time we assemble.
 */
internal fun assembleCardSide(nodes: List<RenderedTemplateNode>): String {
    val sb = StringBuilder()
    for (node in nodes) {
        if (node.hasText()) {
            sb.append(node.text)
        } else {
            // Replacement node: append rslib's already-resolved field text.
            sb.append(node.replacement.currentText)
        }
    }
    return sb.toString()
}
