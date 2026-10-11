package com.dvdutch.recall.compiler

/**
 * Extracts the SIMPLE selectors whose rule set declares `display: none` OR
 * `visibility: hidden`, so the compiler can drop CSS-hidden scaffolding the way
 * real Anki never paints it. (Both properties render nothing; e.g. the Russian
 * Core 5000 deck hides Index/tag/Dispersion scaffolding with
 * `.hidden { visibility: hidden }`.)
 *
 * Direct port of `anki_bridge.compiler`'s `_parse_hidden_css` / `_strip_at_rules`
 * / `_declares_hidden`. Conservative by design (never silently over-hide):
 * only `.class`, `#id`, `tag`, and `tag.class` selectors are honored; anything
 * with combinators/attributes/pseudos — including `tag#id` — is skipped. At-rule
 * blocks (`@media`/`@supports`/…) are stripped whole because their nested rules
 * are runtime-conditional; honoring them unconditionally would over-hide.
 */
class CssHidden private constructor(
    private val tags: Set<String>,
    private val classes: Set<String>,
    private val ids: Set<String>,
    private val tagClasses: Set<Pair<String, String>>,
    // The subset of the above hidden by `display: none` specifically (no box at all, as
    // opposed to `visibility: hidden`'s invisible box). Phone-side only: [matches], the
    // parity-governed test, is unchanged.
    private val displayNone: CssHidden? = null,
) {
    fun matches(tag: String?, classes: List<String>, elId: String): Boolean {
        if (tag != null) {
            if (tag in tags) return true
            for (c in classes) if (Pair(tag, c) in tagClasses) return true
        }
        if (elId.isNotEmpty() && elId in ids) return true
        return classes.any { it in this.classes }
    }

    /**
     * True iff a `display: none` rule hides the element: it then takes no space, where a
     * `visibility: hidden` one keeps its (invisible) box — which is what decides, say,
     * whether a hidden `<li>` still takes its number in the list.
     */
    fun matchesDisplayNone(tag: String?, classes: List<String>, elId: String): Boolean =
        displayNone?.matches(tag, classes, elId) ?: false

    companion object {
        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        // A single declaration value of `none` (optionally `!important`).
        private val DISPLAY_NONE_DECL = Regex("""^none(?:\s*!important)?$""", RegexOption.IGNORE_CASE)

        // A single declaration value of `hidden` (optionally `!important`).
        private val VISIBILITY_HIDDEN_DECL = Regex("""^hidden(?:\s*!important)?$""", RegexOption.IGNORE_CASE)

        // (property, value-matcher) pairs whose presence in a declaration block
        // hides the element's content. `display: none` and `visibility: hidden`
        // are the two CSS ways card templates conceal scaffolding; both render
        // nothing, so both union into the hidden-selector set.
        private val HIDDEN_DECLS = listOf(
            "display" to DISPLAY_NONE_DECL,
            "visibility" to VISIBILITY_HIDDEN_DECL,
        )

        // simple selectors we honor: .class | #id | tag | tag.class
        private val SIMPLE_SELECTOR = Regex(
            "^(?:" +
                "(?<tag>[A-Za-z][\\w-]*)(?:(?<tcls>\\.[\\w-]+))?" +
                "|(?<cls>\\.[\\w-]+)" +
                "|(?<id>#[\\w-]+)" +
                ")$",
        )

        // `selectors { decls }`, no nested braces (at-rules already stripped).
        private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")

        val EMPTY = CssHidden(emptySet(), emptySet(), emptySet(), emptySet())

        fun parse(css: String): CssHidden {
            val lowered = css.lowercase()
            if (css.isEmpty() || (!lowered.contains("display") && !lowered.contains("visibility"))) return EMPTY
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)

            val all = Selectors()
            val noneOnly = Selectors()

            for (m in RULE.findAll(text)) {
                val selectors = m.groupValues[1]
                val decls = m.groupValues[2]
                if (!declaresHidden(decls)) continue
                val displayNone = declaresDisplayNone(decls)
                for (rawSel in selectors.split(",")) {
                    val sm = SIMPLE_SELECTOR.matchEntire(rawSel.trim()) ?: continue
                    all.add(sm)
                    if (displayNone) noneOnly.add(sm)
                }
            }
            return all.build(displayNone = noneOnly.build(displayNone = null))
        }

        /** The simple selectors collected from hiding rules, by kind. */
        private class Selectors {
            val tags = mutableSetOf<String>()
            val classes = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val tagClasses = mutableSetOf<Pair<String, String>>()

            fun add(sm: MatchResult) {
                val cls = sm.groups["cls"]?.value
                val id = sm.groups["id"]?.value
                val tag = sm.groups["tag"]?.value
                val tcls = sm.groups["tcls"]?.value
                when {
                    cls != null -> classes.add(cls.substring(1))
                    id != null -> ids.add(id.substring(1))
                    tag != null -> {
                        val t = tag.lowercase()
                        if (tcls != null) tagClasses.add(Pair(t, tcls.substring(1)))
                        else tags.add(t)
                    }
                }
            }

            fun build(displayNone: CssHidden?) = CssHidden(tags, classes, ids, tagClasses, displayNone)
        }

        /** True iff the declaration block sets `display: none` (see [declaresHidden]). */
        fun declaresDisplayNone(decls: String): Boolean {
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                if (decl.substring(0, idx).trim().lowercase() != "display") continue
                if (DISPLAY_NONE_DECL.matches(decl.substring(idx + 1).trim())) return true
            }
            return false
        }

        /**
         * True iff the declaration block hides content via `display: none` OR
         * `visibility: hidden`.
         * Per-declaration parse: split on `;`, split each on the first `:`, and
         * require an exact property/value match (case-insensitive/trimmed),
         * value optionally followed by `!important`. Per-declaration (not
         * substring) matching keeps `background-display: none`,
         * `display: none-ish`, `x-visibility: hidden`, and
         * `visibility: hidden-ish` from over-hiding.
         */
        fun declaresHidden(decls: String): Boolean {
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx).trim().lowercase()
                val value = decl.substring(idx + 1).trim()
                for ((hiddenProp, valueRe) in HIDDEN_DECLS) {
                    if (prop == hiddenProp && valueRe.matches(value)) return true
                }
            }
            return false
        }

        /**
         * Remove complete at-rule blocks (`@...{...}`, arbitrary nesting) before
         * rule extraction. Statement at-rules with no block (e.g. `@import ...;`)
         * are dropped up to and including the `;`. Brace-depth scanner from each
         * `@` handles nesting.
         */
        private fun stripAtRules(css: String): String {
            val out = StringBuilder()
            var i = 0
            val n = css.length
            while (i < n) {
                val ch = css[i]
                if (ch == '@') {
                    var j = i + 1
                    while (j < n && css[j] != '{' && css[j] != ';') j++
                    if (j < n && css[j] == '{') {
                        var depth = 0
                        while (j < n) {
                            when (css[j]) {
                                '{' -> depth++
                                '}' -> {
                                    depth--
                                    if (depth == 0) {
                                        j++
                                        break
                                    }
                                }
                            }
                            j++
                        }
                        i = j
                        continue
                    }
                    if (j < n) j++
                    i = j
                    continue
                }
                out.append(ch)
                i++
            }
            return out.toString()
        }
    }
}
