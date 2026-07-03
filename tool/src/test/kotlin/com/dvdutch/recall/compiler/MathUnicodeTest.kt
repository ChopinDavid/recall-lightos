package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MathJax / LaTeX -> Unicode subset. Ported verbatim from the bridge's
 * `test_compiler.py` math suite. Delimited math regions are rewritten to a
 * readable Unicode subset, delimiters dropped; non-math text is untouched;
 * unrepresentable structures degrade (never crash, never leak raw `\alpha`).
 */
class MathUnicodeTest {

    private fun m(s: String): String = MathUnicode.transform(s)

    private fun text(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) n.runs.forEach { append(it.s) }
    }

    @Test
    fun mapsMathToUnicode() {
        val cases = listOf(
            // delimiters dropped, superscripts mapped
            "\\(x^2+3x=0\\)" to "x²+3x=0",
            "\\[x^2\\]" to "x²",
            "[\$]x^2[\$]" to "x²",
            "[\$\$]x^2[\$\$]" to "x²",
            // multi-char superscript group
            "\\(x^{10}\\)" to "x¹⁰",
            "\\(Na^+\\)" to "Na⁺",
            "\\(e^{-x}\\)" to "e⁻ˣ",
            // subscripts
            "\\(H_2O\\)" to "H₂O",
            "\\(CO_2\\)" to "CO₂",
            // greek
            "\\(\\alpha + \\beta\\)" to "α + β",
            "\\(\\Omega\\)" to "Ω",
            "\\(\\pi r^2\\)" to "π r²",
            // operators / relations
            "\\(a \\leq b \\neq c\\)" to "a ≤ b ≠ c",
            "\\(3 \\times 4 \\div 2\\)" to "3 × 4 ÷ 2",
            "\\(\\sum_{i=1}^{n} i\\)" to "∑ᵢ₌₁ⁿ i",
            "\\(x \\rightarrow \\infty\\)" to "x → ∞",
            "\\(\\sqrt{x}\\)" to "√x",
            // fraction degrades to readable literal
            "\\(\\frac{1}{2}\\)" to "(1)/(2)",
            "\\(\\frac{a+b}{c}\\)" to "(a+b)/(c)",
            // unknown command degrades to its letters (no raw backslash)
            "\\(\\foobar x\\)" to "foobar x",
            // subscript letters WITH a unicode form map (i,j -> ᵢⱼ)
            "\\(x_{ij}\\)" to "xᵢⱼ",
            // subscript with a char lacking a unicode form degrades to `_body`
            "\\(x_{qz}\\)" to "x_qz",
            // left/right and spacing macros stripped
            "\\(\\left( a \\right)\\)" to "( a )",
            "\\(a \\, b\\)" to "a  b",
            // grouping braces stripped
            "\\({abc}\\)" to "abc",
        )
        for ((src, want) in cases) {
            assertEquals(want, m(src), "for input: $src")
        }
    }

    @Test
    fun leavesNonMathUntouched() {
        assertEquals("plain text, no math", m("plain text, no math"))
        // a stray backslash that is not a delimiter is left alone
        assertEquals("C:\\temp\\alpha", m("C:\\temp\\alpha"))
        // open delimiter with no close: left verbatim (not a math region)
        assertEquals("\\(x^2", m("\\(x^2"))
    }

    @Test
    fun handlesMixedTextAndMath() {
        assertEquals("Solve x²=4 for x.", m("Solve \\(x^2=4\\) for x."))
    }

    @Test
    fun wiredIntoCompile() {
        val nodes = compileHtml("Solve \\(x^2+3x=0\\)", side = "front")
        assertEquals("Solve x²+3x=0", text(nodes))
    }
}
