package com.dvdutch.recall.compiler

/**
 * MathJax / LaTeX -> Unicode-subset rendering. Direct port of
 * `anki_bridge.compiler`'s `_math_to_unicode` / `_render_math` helpers.
 *
 * Math reaches the phone as literal `\(x^2+3x\)` / `\[...\]` / `[$]...[$]` /
 * `[$$]...[$$]` text (rslib passes these through untouched; there is no
 * WebView/JS to render MathJax, and the legacy `[latex]` image path needs an
 * absent TeX toolchain). This transform rewrites the DELIMITED math regions
 * into a readable Unicode subset (`x^2`->`x²`, `\alpha`->α, `\leq`->≤, …),
 * DROPPING the delimiters, and degrades — never crashes — on structures Unicode
 * cannot represent (`\frac{a}{b}`->`(a)/(b)`, matrices, deep nesting).
 *
 * The mapping tables and the scanner MUST stay char-for-char identical to the
 * Python reference; the parity corpus guards this.
 */
internal object MathUnicode {

    // LaTeX command -> Unicode symbol (greek + operators/relations + misc).
    private val SYMBOLS: Map<String, String> = mapOf(
        // lowercase greek
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // uppercase greek
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
        "Omega" to "Ω",
        // relations
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "equiv" to "≡", "approx" to "≈", "cong" to "≅", "sim" to "∼", "simeq" to "≃",
        "propto" to "∝", "ll" to "≪", "gg" to "≫", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "in" to "∈", "notin" to "∉", "ni" to "∋",
        "forall" to "∀", "exists" to "∃", "nexists" to "∄", "mid" to "∣", "parallel" to "∥",
        // binary operators
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·", "ast" to "∗",
        "star" to "⋆", "circ" to "∘", "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖", "wedge" to "∧", "vee" to "∨",
        "land" to "∧", "lor" to "∨", "neg" to "¬", "lnot" to "¬",
        // big operators
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
        "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
        "bigoplus" to "⨁", "bigotimes" to "⨂",
        // arrows
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "gets" to "←",
        "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔", "mapsto" to "↦",
        "uparrow" to "↑", "downarrow" to "↓", "longrightarrow" to "⟶",
        "longleftarrow" to "⟵",
        // misc symbols
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "emptyset" to "∅",
        "varnothing" to "∅", "aleph" to "ℵ", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ",
        "Im" to "ℑ", "wp" to "℘", "angle" to "∠", "triangle" to "△", "square" to "□",
        "diamond" to "⋄", "perp" to "⊥", "top" to "⊤", "bot" to "⊥", "vdash" to "⊢",
        "models" to "⊨", "therefore" to "∴", "because" to "∵", "degree" to "°",
        "prime" to "′", "dagger" to "†", "ddagger" to "‡", "cdots" to "⋯", "ldots" to "…",
        "dots" to "…", "vdots" to "⋮", "ddots" to "⋱", "checkmark" to "✓",
        "surd" to "√", "flat" to "♭", "sharp" to "♯", "natural" to "♮",
        "clubsuit" to "♣", "diamondsuit" to "♦", "heartsuit" to "♥", "spadesuit" to "♠",
        // named function words: keep as their letters (drop the backslash)
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec",
        "csc" to "csc", "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim",
        "max" to "max", "min" to "min", "det" to "det", "gcd" to "gcd", "arg" to "arg",
        "deg" to "deg", "dim" to "dim", "ker" to "ker", "sup" to "sup", "inf" to "inf",
        "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
        // spacing / accents we render as a plain space or nothing
        "quad" to " ", "qquad" to "  ", "space" to " ",
        // escaped literals
        "%" to "%", "&" to "&", "#" to "#", "$" to "$", "_" to "_",
        "{" to "{", "}" to "}", " " to " ",
    )

    // Zero-width / spacing commands stripped entirely (produce empty string).
    private val DROP: Set<String> = setOf(
        "left", "right", "!", ",", ";", ":", "displaystyle",
        "textstyle", "scriptstyle", "limits", "nolimits", "big",
        "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
    )

    // Superscript character map (chars with a Unicode superscript form).
    private val SUPERSCRIPTS: Map<Char, String> = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵", '6' to "⁶",
        '7' to "⁷", '8' to "⁸", '9' to "⁹", '+' to "⁺", '-' to "⁻", '−' to "⁻", '=' to "⁼",
        '(' to "⁽", ')' to "⁾", 'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ",
        'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ",
        'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ",
        'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ",
        'A' to "ᴬ", 'B' to "ᴮ", 'D' to "ᴰ", 'E' to "ᴱ", 'G' to "ᴳ", 'H' to "ᴴ", 'I' to "ᴵ",
        'J' to "ᴶ", 'K' to "ᴷ", 'L' to "ᴸ", 'M' to "ᴹ", 'N' to "ᴺ", 'O' to "ᴼ", 'P' to "ᴾ",
        'R' to "ᴿ", 'T' to "ᵀ", 'U' to "ᵁ", 'V' to "ⱽ", 'W' to "ᵂ",
    )

    // Subscript character map (chars with a Unicode subscript form).
    private val SUBSCRIPTS: Map<Char, String> = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄", '5' to "₅", '6' to "₆",
        '7' to "₇", '8' to "₈", '9' to "₉", '+' to "₊", '-' to "₋", '−' to "₋", '=' to "₌",
        '(' to "₍", ')' to "₎", 'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ",
        'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ",
        's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'x' to "ₓ",
    )

    // Math delimiter pairs. Order matters: the longer Anki fence (`[$$]`) must be
    // tried before `[$]`. Each region's inner text is rendered, delimiters dropped.
    private val DELIMS: List<Pair<String, String>> = listOf(
        "\\[" to "\\]",
        "\\(" to "\\)",
        "[$$]" to "[$$]",
        "[$]" to "[$]",
    )

    /**
     * Rewrite math DELIMITER regions in [text] to a Unicode subset, dropping the
     * delimiters; leave non-math text untouched. Never raises.
     */
    fun transform(text: String): String {
        if (text.isEmpty() || (!text.contains("\\") && !text.contains("[$"))) return text
        val out = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            for ((openD, closeD) in DELIMS) {
                if (text.startsWith(openD, i)) {
                    val start = i + openD.length
                    val end = text.indexOf(closeD, start)
                    if (end != -1) {
                        out.append(renderMath(text.substring(start, end)))
                        i = end + closeD.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** Map every char of [body] through [table]; null if any char has no form. */
    private fun mapScript(body: String, table: Map<Char, String>): String? {
        val out = StringBuilder()
        for (ch in body) {
            val mapped = table[ch] ?: return null
            out.append(mapped)
        }
        return out.toString()
    }

    /**
     * If s[i] == '{', return (inner-through-matching-brace, index-after-'}').
     * Otherwise return the single char (or "" at end), index-after-char.
     */
    private fun readGroup(s: String, i: Int): Pair<String, Int> {
        val n = s.length
        if (i >= n) return "" to i
        if (s[i] == '{') {
            var depth = 0
            var j = i
            while (j < n) {
                when (s[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(i + 1, j) to (j + 1)
                    }
                }
                j++
            }
            return s.substring(i + 1) to n // unbalanced: take the rest
        }
        return s[i].toString() to (i + 1)
    }

    /** ASCII-letter check, matching Python `ch.isascii() and ch.isalpha()`. */
    private fun Char.isAsciiAlpha(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /**
     * Render the INNER content of a math region to a Unicode subset. Pure,
     * deterministic, never raises. Recurses into frac/sqrt/group args.
     */
    private fun renderMath(s: String): String {
        val out = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            when {
                ch == '\\' -> {
                    // read the command name: a run of ascii letters, OR a single
                    // non-letter (escaped symbol like `\,` `\{` `\%`).
                    val j = i + 1
                    val name: String
                    if (j < n && s[j].isAsciiAlpha()) {
                        var k = j
                        while (k < n && s[k].isAsciiAlpha()) k++
                        name = s.substring(j, k)
                        i = k
                    } else if (j < n) {
                        name = s[j].toString()
                        i = j + 1
                    } else {
                        name = ""
                        i = j
                    }
                    when {
                        name == "frac" || name == "dfrac" || name == "tfrac" -> {
                            val (a, i1) = readGroup(s, i)
                            val (b, i2) = readGroup(s, i1)
                            i = i2
                            out.append("(").append(renderMath(a)).append(")/(")
                                .append(renderMath(b)).append(")")
                        }
                        name == "sqrt" -> {
                            val (arg, i1) = readGroup(s, i)
                            i = i1
                            out.append("√").append(renderMath(arg))
                        }
                        name == "text" || name == "mathrm" || name == "mathbf" ||
                            name == "mathit" || name == "operatorname" ||
                            name == "mathcal" || name == "mathbb" -> {
                            val (arg, i1) = readGroup(s, i)
                            i = i1
                            out.append(renderMath(arg))
                        }
                        name in DROP -> { /* spacing / sizing: emit nothing */ }
                        SYMBOLS.containsKey(name) -> out.append(SYMBOLS.getValue(name))
                        else -> out.append(name) // unknown command: degrade to its letters
                    }
                }
                ch == '^' || ch == '_' -> {
                    val (body, i1) = readGroup(s, i + 1)
                    i = i1
                    val rendered = renderMath(body)
                    val table = if (ch == '^') SUPERSCRIPTS else SUBSCRIPTS
                    val mapped = mapScript(rendered, table)
                    out.append(mapped ?: (ch + rendered))
                }
                ch == '{' || ch == '}' -> i++ // strip stray grouping braces
                ch == '~' -> { out.append(' '); i++ } // non-breaking space macro
                else -> { out.append(ch); i++ }
            }
        }
        return out.toString()
    }
}
