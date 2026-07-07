package com.dvdutch.recall.compiler

/**
 * Fix D — extracts BOUNDED per-class vertical margins from the notetype CSS so the
 * compiled card reproduces the deck's vertical rhythm (e.g. `.prettify-tags`'s 2em
 * top gap, the `.prettify-divider--answer`'s .7em breathing room). Only the same
 * simple class selectors [CssHidden]/[CssTextAlign] honor are read (`.class` and
 * the class of `tag.class`), keeping the discipline uniform.
 *
 * Scope, deliberately narrow:
 *  - `margin-top` / `margin-bottom`, and the vertical components of the `margin`
 *    shorthand (1/2/3/4-value forms) — horizontal margins are ignored;
 *  - lengths in `em` or `px` ONLY; `auto`, `%`, and `calc(...)` record nothing;
 *  - every margin is normalized to em (px ÷ [BASE_PX]) and capped at [MAX_EM] so a
 *    pathological deck can't blow the layout apart;
 *  - later declarations win (CSS source order), so a `margin-top` longhand
 *    overrides an earlier `margin` shorthand.
 *
 * Adjacent-margin collapse (max, not sum) is applied by the UI when it stacks the
 * blocks — this class only reports each class's own top/bottom em.
 */
class CssMargins private constructor(
    private val topEm: Map<String, Float>,
    private val bottomEm: Map<String, Float>,
) {
    /** The top margin (em) of the first of [classes] that declares one, else 0. */
    fun topEmForClasses(classes: List<String>): Float {
        for (c in classes) topEm[c]?.let { return it }
        return 0f
    }

    /** The bottom margin (em) of the first of [classes] that declares one, else 0. */
    fun bottomEmForClasses(classes: List<String>): Float {
        for (c in classes) bottomEm[c]?.let { return it }
        return 0f
    }

    val isEmpty: Boolean get() = topEm.isEmpty() && bottomEm.isEmpty()

    companion object {
        /** em → × the card's base text size. px are divided by this to reach em. */
        const val BASE_PX = 30f

        /** Hard cap (em) on any single margin so a weird deck can't blow up layout. */
        const val MAX_EM = 3f

        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        private val SIMPLE_SELECTOR = Regex(
            "^(?:" +
                "(?<tag>[A-Za-z][\\w-]*)(?:(?<tcls>\\.[\\w-]+))?" +
                "|(?<cls>\\.[\\w-]+)" +
                "|(?<id>#[\\w-]+)" +
                ")$",
        )

        private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")

        // A bounded length: a bare number with an `em` or `px` unit. auto/%/calc
        // etc. do NOT match, so they record nothing.
        private val LENGTH = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))(em|px)$""", RegexOption.IGNORE_CASE)

        val EMPTY = CssMargins(emptyMap(), emptyMap())

        fun parse(css: String): CssMargins {
            if (css.isEmpty() || !css.contains("margin", ignoreCase = true)) return EMPTY
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)

            val topEm = mutableMapOf<String, Float>()
            val bottomEm = mutableMapOf<String, Float>()

            for (m in RULE.findAll(text)) {
                val selectors = m.groupValues[1]
                val (top, bottom) = verticalMarginsOf(m.groupValues[2])
                if (top == null && bottom == null) continue
                for (rawSel in selectors.split(",")) {
                    val cls = classOf(rawSel.trim()) ?: continue
                    if (top != null) topEm[cls] = top
                    if (bottom != null) bottomEm[cls] = bottom
                }
            }
            return CssMargins(topEm, bottomEm)
        }

        /**
         * The (top, bottom) vertical margins in em for a declaration block, honoring
         * source order (later wins). A `null` component means "not declared here".
         */
        private fun verticalMarginsOf(decls: String): Pair<Float?, Float?> {
            var top: Float? = null
            var bottom: Float? = null
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                val prop = decl.substring(0, idx).trim().lowercase()
                val value = decl.substring(idx + 1).trim()
                when (prop) {
                    "margin-top" -> lengthEm(value)?.let { top = it }
                    "margin-bottom" -> lengthEm(value)?.let { bottom = it }
                    "margin" -> {
                        val (t, b) = shorthandVertical(value)
                        // The shorthand sets BOTH sides; a subsequent longhand may
                        // then override one. Only overwrite when the shorthand
                        // actually parsed a bounded length for that side.
                        if (t != null) top = t
                        if (b != null) bottom = b
                    }
                }
            }
            return top to bottom
        }

        /**
         * The top/bottom em of a `margin` shorthand's 1/2/3/4-value form. Any side
         * that isn't a bounded em/px length yields null for that side (e.g. `auto`).
         */
        private fun shorthandVertical(value: String): Pair<Float?, Float?> {
            val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (parts.size) {
                1 -> lengthEm(parts[0]).let { it to it }
                2, 3 -> lengthEm(parts[0]).let { it to it } // top=[0]; bottom=[2] if present
                    .let { (t, _) -> t to (if (parts.size == 3) lengthEm(parts[2]) else t) }
                4 -> lengthEm(parts[0]) to lengthEm(parts[2])
                else -> null to null
            }
        }

        /**
         * A bounded em/px length normalized to em, capped at [MAX_EM]. Zero yields
         * 0f; a negative margin is ignored (returns null, treated as "not declared")
         * — negative pull-ups aren't modelled by our additive stacking.
         */
        private fun lengthEm(value: String): Float? {
            val mm = LENGTH.matchEntire(value.trim()) ?: return null
            val n = mm.groupValues[1].toFloatOrNull() ?: return null
            val em = if (mm.groupValues[2].lowercase() == "px") n / BASE_PX else n
            if (em < 0f) return null
            return em.coerceAtMost(MAX_EM)
        }

        private fun classOf(rawSel: String): String? {
            val sm = SIMPLE_SELECTOR.matchEntire(rawSel) ?: return null
            sm.groups["cls"]?.value?.let { return it.substring(1) }
            sm.groups["tcls"]?.value?.let { return it.substring(1) }
            return null
        }

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
