package com.dvdutch.recall.compiler

/**
 * Task 1 — bounded per-class `font-size` extraction, keyed by the SAME simple class
 * selectors [CssHidden]/[CssTextAlign]/[CssMargins] honor (`.class` and the class of
 * `tag.class`). It powers a per-node font SCALE = (class font px) / (card base px),
 * so the deck's size hierarchy (tiny corners, medium sentence, small translation) is
 * reproduced WITHOUT hardcoding absolute px — the LP3 base text size stays
 * authoritative; we only multiply it.
 *
 * Scope, deliberately narrow (unknown/unparseable records nothing → scale 1):
 *  - `px` values, and a single-level `var(--name)` resolved against `:root`
 *    (reusing the same var machinery as [CssTextAlign]);
 *  - `em` ONLY, resolved against the CARD BASE (so `0.5em` on a 28px base → 14px);
 *  - `%`, `rem`, and keywords (`larger`, `smaller`, …) are ignored;
 *  - later declaration wins (CSS source order);
 *  - the resulting scale is clamped to [[MIN_SCALE], [MAX_SCALE]].
 *
 * Inheritance is nearest-ancestor: the compiler passes an element's own classes
 * FIRST, then its ancestors', and [pxForClasses]/[scaleForClasses] take the first
 * class that carries a size — there is no multiplicative cascade beyond that.
 */
class CssFontSize private constructor(
    private val classSize: Map<String, Size>,
) {
    /** A parsed `font-size`: an absolute px, or an `em` multiple of the card base. */
    private sealed interface Size {
        data class Px(val px: Float) : Size
        data class Em(val em: Float) : Size
    }

    /**
     * The absolute px `font-size` of the first of [classes] that declares an absolute
     * (px or var→px) size, or null. `em` sizes are NOT absolute and yield null here;
     * they only participate via [scaleForClasses]. Used to derive the card base and in
     * tests.
     */
    fun pxForClasses(classes: List<String>): Float? {
        for (c in classes) {
            when (val s = classSize[c]) {
                is Size.Px -> return s.px
                is Size.Em -> return null // nearest declared size is em → not an absolute px
                null -> {}
            }
        }
        return null
    }

    /**
     * The card's base font px: the absolute px on the first of [rootClasses] (the
     * card root class, e.g. `prettify-flashcard`) that declares one, else
     * [DEFAULT_BASE_PX]. em on the root class is not meaningful (nothing to be
     * relative to) so it also falls back.
     */
    fun cardBasePx(rootClasses: List<String>): Float = pxForClasses(rootClasses) ?: DEFAULT_BASE_PX

    /**
     * The font SCALE for the first of [classes] that declares a size, relative to
     * [basePx], clamped to [[MIN_SCALE], [MAX_SCALE]]. A class with no declared size
     * (or nothing declared at all) yields exactly 1 (no scaling).
     */
    fun scaleForClasses(classes: List<String>, basePx: Float): Float {
        for (c in classes) {
            val s = classSize[c] ?: continue
            val px = when (s) {
                is Size.Px -> s.px
                is Size.Em -> s.em * basePx
            }
            if (basePx <= 0f) return 1f
            return (px / basePx).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        return 1f
    }

    val isEmpty: Boolean get() = classSize.isEmpty()

    companion object {
        /** Card base px when the root class declares no font-size (the deck's regular). */
        const val DEFAULT_BASE_PX = 28f

        /** Scale clamp — a corner can't shrink below, a heading can't grow above. */
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 1.5f

        private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        private val SIMPLE_SELECTOR = Regex(
            "^(?:" +
                "(?<tag>[A-Za-z][\\w-]*)(?:(?<tcls>\\.[\\w-]+))?" +
                "|(?<cls>\\.[\\w-]+)" +
                "|(?<id>#[\\w-]+)" +
                ")$",
        )

        private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")
        private val VAR_REF = Regex("""var\(\s*(--[\w-]+)\s*(?:,[^)]*)?\)""")
        private val CUSTOM_PROP = Regex("""(--[\w-]+)\s*:\s*([^;]+)""")

        // px or em length only (leading sign allowed). %, rem, keywords do NOT match.
        private val PX = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))px$""", RegexOption.IGNORE_CASE)
        private val EM = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))em$""", RegexOption.IGNORE_CASE)

        val EMPTY = CssFontSize(emptyMap())

        fun parse(css: String): CssFontSize {
            if (css.isEmpty() || !css.contains("font-size", ignoreCase = true)) return EMPTY
            var text = CSS_COMMENT.replace(css, "")
            text = stripAtRules(text)
            val rootVars = collectRootVars(text)
            val classSize = mutableMapOf<String, Size>()
            for (m in RULE.findAll(text)) {
                val size = fontSizeOf(m.groupValues[2], rootVars) ?: continue
                for (rawSel in m.groupValues[1].split(",")) {
                    val cls = classOf(rawSel.trim()) ?: continue
                    classSize.putIfAbsent(cls, size)
                }
            }
            return CssFontSize(classSize)
        }

        /**
         * The winning `font-size` in [decls] as a [Size], resolving a single
         * `var(--name)` against [rootVars]. Later declarations win. `%`/`rem`/keyword/
         * unresolvable-var → null (nothing recorded, so scale stays 1).
         */
        private fun fontSizeOf(decls: String, rootVars: Map<String, String>): Size? {
            var result: Size? = null
            for (decl in decls.split(";")) {
                val idx = decl.indexOf(':')
                if (idx < 0) continue
                if (decl.substring(0, idx).trim().lowercase() != "font-size") continue
                var value = decl.substring(idx + 1).trim()
                val varMatch = VAR_REF.find(value)
                if (varMatch != null) {
                    value = rootVars[varMatch.groupValues[1]]?.trim() ?: continue
                }
                sizeOf(value)?.let { result = it }
            }
            return result
        }

        private fun sizeOf(value: String): Size? {
            val v = value.trim()
            PX.matchEntire(v)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { return Size.Px(it) } }
            EM.matchEntire(v)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { return Size.Em(it) } }
            return null
        }

        private fun classOf(rawSel: String): String? {
            val sm = SIMPLE_SELECTOR.matchEntire(rawSel) ?: return null
            sm.groups["cls"]?.value?.let { return it.substring(1) }
            sm.groups["tcls"]?.value?.let { return it.substring(1) }
            return null
        }

        private fun collectRootVars(css: String): Map<String, String> {
            val vars = mutableMapOf<String, String>()
            for (m in RULE.findAll(css)) {
                if (m.groupValues[1].split(",").none { it.trim() == ":root" }) continue
                for (cm in CUSTOM_PROP.findAll(m.groupValues[2])) {
                    vars.putIfAbsent(cm.groupValues[1], cm.groupValues[2].trim())
                }
            }
            return vars
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
