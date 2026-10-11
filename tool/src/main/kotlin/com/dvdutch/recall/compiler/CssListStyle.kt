package com.dvdutch.recall.compiler

/**
 * The CSS `list-style-type` keywords the compiler renders as a text marker before a
 * list item, plus the browser defaults a `<ul>`/`<ol>` falls back to.
 */
enum class ListStyleType {
    NONE, DISC, CIRCLE, SQUARE, DECIMAL, LOWER_ALPHA, UPPER_ALPHA, LOWER_ROMAN, UPPER_ROMAN;

    /**
     * The marker for the [n]th item (1-based), with the trailing space that stands in
     * for a browser's gap between marker and text; empty for [NONE]. An alphabetic or
     * roman style counts like CSS does (`aa` after `z`; decimal when roman can't).
     */
    fun marker(n: Int): String = when (this) {
        NONE -> ""
        DISC -> "• "
        CIRCLE -> "◦ "
        SQUARE -> "▪ "
        DECIMAL -> "$n. "
        LOWER_ALPHA -> alpha(n).lowercase() + ". "
        UPPER_ALPHA -> alpha(n) + ". "
        LOWER_ROMAN -> roman(n).lowercase() + ". "
        UPPER_ROMAN -> roman(n) + ". "
    }

    companion object {
        /** A browser's default: `<ol>` counts; `<ul>` is a disc, then a circle, then squares as it nests. */
        fun default(ordered: Boolean, depth: Int): ListStyleType = when {
            ordered -> DECIMAL
            depth <= 1 -> DISC
            depth == 2 -> CIRCLE
            else -> SQUARE
        }

        /** The type a CSS keyword names, or null for one that isn't rendered (`disclosure-closed`, …). */
        fun fromKeyword(keyword: String): ListStyleType? = when (keyword.trim().lowercase()) {
            "none" -> NONE
            "disc" -> DISC
            "circle" -> CIRCLE
            "square" -> SQUARE
            "decimal" -> DECIMAL
            "lower-alpha", "lower-latin" -> LOWER_ALPHA
            "upper-alpha", "upper-latin" -> UPPER_ALPHA
            "lower-roman" -> LOWER_ROMAN
            "upper-roman" -> UPPER_ROMAN
            else -> null
        }

        /** The type an `<ol type="…">` attribute names (`1`, `a`, `A`, `i`, `I`), or null. */
        fun fromTypeAttr(value: String): ListStyleType? = when (value.trim()) {
            "1" -> DECIMAL
            "a" -> LOWER_ALPHA
            "A" -> UPPER_ALPHA
            "i" -> LOWER_ROMAN
            "I" -> UPPER_ROMAN
            else -> null
        }

        // CSS alphabetic counting: a…z, then aa, ab, …; non-positive counts fall back to decimal.
        private fun alpha(n: Int): String {
            if (n <= 0) return n.toString()
            val sb = StringBuilder()
            var v = n
            while (v > 0) {
                v--
                sb.append('A' + v % 26)
                v /= 26
            }
            return sb.reverse().toString()
        }

        private val ROMAN = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )

        // CSS roman numerals cover 1–3999; anything else counts in decimal.
        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val sb = StringBuilder()
            var v = n
            for ((value, numeral) in ROMAN) while (v >= value) { sb.append(numeral); v -= value }
            return sb.toString()
        }
    }
}

/**
 * The `list-style-type` (or the keyword inside a `list-style` shorthand) declared for
 * the same simple selectors the other Css* parsers honor: `.class`, `#id`, `tag` and
 * `tag.class`. Lets a deck turn a bullet off (`li.h { list-style-type: none }`) or pick
 * another, the way the Russian Core 5000 etymology lists do. Conservative like
 * [CssHidden]: combinators, attributes and pseudo-classes are skipped, at-rule blocks
 * are stripped whole, and nothing declared means the browser default applies.
 */
class CssListStyle private constructor(
    private val tags: Map<String, ListStyleType>,
    private val classes: Map<String, ListStyleType>,
    private val ids: Map<String, ListStyleType>,
    private val tagClasses: Map<Pair<String, String>, ListStyleType>,
) {
    /** The declared type for an element, by CSS specificity (id, then class, then tag), or null. */
    fun typeFor(tag: String, classes: List<String>, elId: String): ListStyleType? {
        if (elId.isNotEmpty()) ids[elId]?.let { return it }
        for (c in classes) tagClasses[Pair(tag, c)]?.let { return it }
        for (c in classes) this.classes[c]?.let { return it }
        return tags[tag]
    }

    companion object {
        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

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

        val EMPTY = CssListStyle(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun parse(css: String): CssListStyle {
            if (!css.contains("list-style", ignoreCase = true)) return EMPTY
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)

            val tags = mutableMapOf<String, ListStyleType>()
            val classes = mutableMapOf<String, ListStyleType>()
            val ids = mutableMapOf<String, ListStyleType>()
            val tagClasses = mutableMapOf<Pair<String, String>, ListStyleType>()

            for (m in RULE.findAll(text)) {
                val type = declaredType(m.groupValues[2]) ?: continue
                for (rawSel in m.groupValues[1].split(",")) {
                    val sm = SIMPLE_SELECTOR.matchEntire(rawSel.trim()) ?: continue
                    val cls = sm.groups["cls"]?.value
                    val id = sm.groups["id"]?.value
                    val tag = sm.groups["tag"]?.value
                    val tcls = sm.groups["tcls"]?.value
                    when {
                        cls != null -> classes[cls.substring(1)] = type
                        id != null -> ids[id.substring(1)] = type
                        tag != null -> {
                            val t = tag.lowercase()
                            if (tcls != null) tagClasses[Pair(t, tcls.substring(1))] = type else tags[t] = type
                        }
                    }
                }
            }
            return CssListStyle(tags, classes, ids, tagClasses)
        }

        /**
         * The type a declaration block sets: a `list-style-type: <keyword>`, or the first
         * known keyword in a `list-style` shorthand. Per-declaration, the last one wins
         * (CSS source order); an unknown keyword declares nothing.
         */
        fun declaredType(decls: String): ListStyleType? {
            var result: ListStyleType? = null
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx).trim().lowercase()
                val value = decl.substring(idx + 1).trim().removeSuffix("!important").trim()
                when (prop) {
                    "list-style-type" -> ListStyleType.fromKeyword(value)?.let { result = it }
                    "list-style" -> value.split(' ').firstNotNullOfOrNull { ListStyleType.fromKeyword(it) }?.let { result = it }
                }
            }
            return result
        }

        // Remove complete at-rule blocks (`@...{...}`) and statement at-rules (`@import ...;`).
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
