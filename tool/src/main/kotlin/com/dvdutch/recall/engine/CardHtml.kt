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
 * The `FrontSide` special-case DOES apply here: rslib does NOT resolve
 * `{{FrontSide}}` on the answer side — it emits a replacement node whose
 * `fieldName == "FrontSide"` with an EMPTY `currentText`, expecting the client to
 * inject the already-rendered question HTML (this is exactly what the reference's
 * `applyCustomFilters(anodes, frontSide = qout.text)` does). We therefore render
 * the front first and pass it as [frontSide]; without this, the whole front line
 * vanishes from the answer (only `<hr> + Back` survive). A `null` [frontSide]
 * (the question side, which never contains `{{FrontSide}}`) leaves the empty
 * `currentText` untouched, matching the reference's `frontSide = null` question call.
 */
internal fun assembleCardSide(
    nodes: List<RenderedTemplateNode>,
    frontSide: String? = null,
): String {
    val sb = StringBuilder()
    for (node in nodes) {
        if (node.hasText()) {
            sb.append(node.text)
        } else {
            val replacement = node.replacement
            // Inject the rendered question into the answer's {{FrontSide}} node,
            // which rslib leaves empty. Mirrors TemplateManager.applyCustomFilters.
            if (replacement.fieldName == "FrontSide" && frontSide != null) {
                sb.append(frontSide)
            } else {
                // Replacement node: append rslib's already-resolved field text.
                sb.append(replacement.currentText)
            }
        }
    }
    return sb.toString()
}
