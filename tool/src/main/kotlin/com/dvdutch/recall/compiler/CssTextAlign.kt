package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign

/**
 * Extracts, from the notetype CSS, two layout signals keyed by SIMPLE class
 * selector (same conservative discipline as [CssHidden] — only `.class` and the
 * class of `tag.class`, no combinators/attributes/pseudos):
 *
 *  1. `text-align` per class (mapped to [BlockAlign]; `left`/`justify`/`start` →
 *     START, `center` → CENTER, `right`/`end` → END). A `var(--name)` value is
 *     resolved ONE level against a `:root { --name: value }` definition (no
 *     cascade); an unresolvable var records nothing.
 *  2. which classes declare `display: flex` with an unset or `row` flex-direction
 *     (so [RenderCompiler] can compile the element's children as a [RowNode]).
 *
 * Both are best-effort and conservative: unknown/unparseable input records
 * nothing and the compiler falls back to its default vertical linearization.
 */
class CssTextAlign private constructor(
    private val classAlign: Map<String, BlockAlign>,
    private val flexRowClasses: Set<String>,
) {
    /** The [BlockAlign] for the first of [classes] that carries one, or null. */
    fun alignForClasses(classes: List<String>): BlockAlign? {
        for (c in classes) classAlign[c]?.let { return it }
        return null
    }

    /** True iff any of [classes] resolved to `display: flex` (row/unset direction). */
    fun isFlexRow(classes: List<String>): Boolean = classes.any { it in flexRowClasses }

    val isEmpty: Boolean get() = classAlign.isEmpty() && flexRowClasses.isEmpty()

    companion object {
        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        // simple selectors we honor: .class | #id | tag | tag.class (id/tag are
        // ignored here; we only key on the class component of .class / tag.class).
        private val SIMPLE_SELECTOR = Regex(
            "^(?:" +
                "(?<tag>[A-Za-z][\\w-]*)(?:(?<tcls>\\.[\\w-]+))?" +
                "|(?<cls>\\.[\\w-]+)" +
                "|(?<id>#[\\w-]+)" +
                ")$",
        )

        // `selectors { decls }`, no nested braces (at-rules already stripped).
        private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")

        // A `var(--name[, fallback])` reference; we resolve only the --name part.
        private val VAR_REF = Regex("""var\(\s*(--[\w-]+)\s*(?:,[^)]*)?\)""")

        // A `--name: value` custom-property declaration (used inside :root).
        private val CUSTOM_PROP = Regex("""(--[\w-]+)\s*:\s*([^;]+)""")

        val EMPTY = CssTextAlign(emptyMap(), emptySet())

        fun parse(css: String): CssTextAlign {
            val lowered = css.lowercase()
            if (css.isEmpty() ||
                (!lowered.contains("text-align") && !lowered.contains("display"))
            ) {
                return EMPTY
            }
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)

            val rootVars = collectRootVars(text)
            val classAlign = mutableMapOf<String, BlockAlign>()
            val flexRowClasses = mutableSetOf<String>()

            for (m in RULE.findAll(text)) {
                val selectors = m.groupValues[1]
                val decls = m.groupValues[2]
                val align = textAlignOf(decls, rootVars)
                val flexRow = declaresFlexRow(decls)
                if (align == null && !flexRow) continue
                for (rawSel in selectors.split(",")) {
                    val cls = classOf(rawSel.trim()) ?: continue
                    if (align != null) classAlign.putIfAbsent(cls, align)
                    if (flexRow) flexRowClasses.add(cls)
                }
            }
            return CssTextAlign(classAlign, flexRowClasses)
        }

        /** The class component of a `.class` or `tag.class` simple selector, else null. */
        private fun classOf(rawSel: String): String? {
            val sm = SIMPLE_SELECTOR.matchEntire(rawSel) ?: return null
            sm.groups["cls"]?.value?.let { return it.substring(1) }
            sm.groups["tcls"]?.value?.let { return it.substring(1) }
            return null
        }

        /** `:root { --name: value }` custom properties, one flat map (no cascade). */
        private fun collectRootVars(css: String): Map<String, String> {
            val vars = mutableMapOf<String, String>()
            for (m in RULE.findAll(css)) {
                val selectors = m.groupValues[1]
                if (selectors.split(",").none { it.trim() == ":root" }) continue
                for (cm in CUSTOM_PROP.findAll(m.groupValues[2])) {
                    vars.putIfAbsent(cm.groupValues[1], cm.groupValues[2].trim())
                }
            }
            return vars
        }

        /**
         * The winning `text-align` in [decls] mapped to [BlockAlign], resolving a
         * single `var(--name)` against [rootVars]. Later declarations win (CSS
         * source order). Returns null when no resolvable text-align is present.
         */
        private fun textAlignOf(decls: String, rootVars: Map<String, String>): BlockAlign? {
            var result: BlockAlign? = null
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx).trim().lowercase()
                if (prop != "text-align") continue
                var value = decl.substring(idx + 1).trim()
                val varMatch = VAR_REF.find(value)
                if (varMatch != null) {
                    value = rootVars[varMatch.groupValues[1]]?.trim() ?: continue
                }
                mapAlign(value)?.let { result = it }
            }
            return result
        }

        private fun mapAlign(value: String): BlockAlign? = when (value.trim().lowercase()) {
            "left", "start", "justify" -> BlockAlign.START
            "center" -> BlockAlign.CENTER
            "right", "end" -> BlockAlign.END
            else -> null
        }

        /**
         * True iff [decls] declares `display: flex` (or `inline-flex`) with an
         * unset or `row`/`row-reverse` flex-direction. `flex-direction: column`
         * disqualifies (we only lay out rows).
         */
        private fun declaresFlexRow(decls: String): Boolean {
            var isFlex = false
            var isColumn = false
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx).trim().lowercase()
                val value = decl.substring(idx + 1).trim().lowercase()
                when (prop) {
                    "display" -> if (value == "flex" || value == "inline-flex") isFlex = true
                    "flex-direction" -> if (value.startsWith("column")) isColumn = true
                }
            }
            return isFlex && !isColumn
        }

        /** Remove complete at-rule blocks; identical scanner to [CssHidden]. */
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
