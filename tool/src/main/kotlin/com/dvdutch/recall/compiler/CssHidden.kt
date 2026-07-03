package com.dvdutch.recall.compiler

/**
 * Extracts the SIMPLE selectors whose rule set declares `display: none`, so the
 * compiler can drop CSS-hidden scaffolding the way real Anki never paints it.
 *
 * Direct port of `anki_bridge.compiler`'s `_parse_hidden_css` / `_strip_at_rules`
 * / `_declares_display_none`. Conservative by design (never silently over-hide):
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
) {
    fun matches(tag: String?, classes: List<String>, elId: String): Boolean {
        if (tag != null) {
            if (tag in tags) return true
            for (c in classes) if (Pair(tag, c) in tagClasses) return true
        }
        if (elId.isNotEmpty() && elId in ids) return true
        return classes.any { it in this.classes }
    }

    companion object {
        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        // A single declaration value of `none` (optionally `!important`).
        private val DISPLAY_NONE_DECL = Regex("""^none(?:\s*!important)?$""", RegexOption.IGNORE_CASE)

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
            if (css.isEmpty() || !css.lowercase().contains("display")) return EMPTY
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)

            val tags = mutableSetOf<String>()
            val classes = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val tagClasses = mutableSetOf<Pair<String, String>>()

            for (m in RULE.findAll(text)) {
                val selectors = m.groupValues[1]
                val decls = m.groupValues[2]
                if (!declaresDisplayNone(decls)) continue
                for (rawSel in selectors.split(",")) {
                    val sm = SIMPLE_SELECTOR.matchEntire(rawSel.trim()) ?: continue
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
            }
            return CssHidden(tags, classes, ids, tagClasses)
        }

        /**
         * True iff the declaration block contains an exact `display: none`.
         * Per-declaration parse: split on `;`, split each on the first `:`, and
         * require property exactly `display` and value exactly `none`
         * (case-insensitive/trimmed), value optionally followed by `!important`.
         */
        fun declaresDisplayNone(decls: String): Boolean {
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx)
                val value = decl.substring(idx + 1)
                if (prop.trim().lowercase() == "display" &&
                    DISPLAY_NONE_DECL.matches(value.trim())
                ) {
                    return true
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
