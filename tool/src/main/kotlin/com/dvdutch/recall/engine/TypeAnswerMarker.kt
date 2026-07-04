package com.dvdutch.recall.engine

/**
 * The literal `[[type:...]]` marker rslib substitutes for a `{{type:Field}}`
 * template replacement.
 *
 * rslib does NOT compare the typed answer itself — it renders the type field
 * reference as a literal `[[type:Field]]` marker in the card HTML and expects the
 * CLIENT to (a) strip it (so it never surfaces as garbage text) and, on the
 * question side, (b) resolve the referenced field as the expected answer and, at
 * reveal, drive `compareAnswer`. This is engine-level pre-processing that never
 * touches the parity-governed HTML→node compilers.
 *
 * Three shapes exist, matching AnkiDroid's type-answer handling:
 *   - `[[type:Field]]`        plain, case-sensitive compare;
 *   - `[[type:nc:Field]]`     "no combining"/no-case compare — case-insensitive;
 *   - `[[type:cloze:Field]]`  cloze type-answer (OUT OF SCOPE v1: stripped only).
 */
data class TypeAnswerMarker(
    /** The referenced note field name — the field whose text is the expected answer. */
    val field: String,
    /** True for `[[type:nc:Field]]`: compare case-insensitively. */
    val noCase: Boolean,
    /** True for `[[type:cloze:Field]]`: a cloze type-answer (v1 strips, no affordance). */
    val cloze: Boolean,
) {
    companion object {
        // [[type:Field]] / [[type:nc:Field]] / [[type:cloze:Field]]. The optional
        // `nc:` or `cloze:` prefix is captured so we can classify; the field name is
        // everything up to the closing ]].
        private val REGEX = Regex("""\[\[type:(nc:|cloze:)?(.*?)]]""")

        /**
         * Returns the first type-answer marker in [html], or null if there is none.
         * The field name is taken verbatim (trimmed of surrounding whitespace only).
         */
        fun find(html: String): TypeAnswerMarker? {
            val m = REGEX.find(html) ?: return null
            val prefix = m.groupValues[1]
            return TypeAnswerMarker(
                field = m.groupValues[2].trim(),
                noCase = prefix == "nc:",
                cloze = prefix == "cloze:",
            )
        }

        /**
         * Removes every `[[type:...]]` marker from [html], so the marker never reaches the
         * compiler as visible text. Marker-free HTML is returned BYTE-FOR-BYTE unchanged —
         * so normal cards keep their exact former compile input (no incidental trimming) and
         * only a side that actually carried a marker is altered (and then trimmed, since the
         * marker typically sat after a trailing space).
         */
        fun strip(html: String): String {
            if (!REGEX.containsMatchIn(html)) return html
            return REGEX.replace(html, "").trim()
        }
    }
}
