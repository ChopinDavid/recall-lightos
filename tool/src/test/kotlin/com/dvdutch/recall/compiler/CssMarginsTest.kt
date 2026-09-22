package com.dvdutch.recall.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fix D — bounded per-class vertical margins (`margin-top` / `margin-bottom`, and
 * the vertical components of the `margin` shorthand) in em/px only. em is measured
 * in the card's base text size (× [CssMargins.EM_UNITS]); px is passed through as a
 * raw px count the UI converts. auto/percent/calc are ignored, and every margin is
 * capped at [CssMargins.MAX_EM] em. Simple class selectors only.
 */
class CssMarginsTest {

    private fun top(css: String, cls: String): Float =
        CssMargins.parse(css).topEmForClasses(listOf(cls))

    private fun bottom(css: String, cls: String): Float =
        CssMargins.parse(css).bottomEmForClasses(listOf(cls))

    @Test
    fun explicitMarginTopBottom() {
        val css = ".tags { margin-top: 2em; margin-bottom: 1em }"
        assertEquals(2f, top(css, "tags"))
        assertEquals(1f, bottom(css, "tags"))
    }

    @Test
    fun shorthandOneValueAppliesToBoth() {
        // `margin: .7em` -> top and bottom both .7em (all four sides).
        val css = ".divider { margin: .7em }"
        assertEquals(0.7f, top(css, "divider"))
        assertEquals(0.7f, bottom(css, "divider"))
    }

    @Test
    fun shorthandTwoValuesVerticalHorizontal() {
        // `margin: 2em 1em` -> vertical 2em (top+bottom), horizontal ignored.
        val css = ".x { margin: 2em 1em }"
        assertEquals(2f, top(css, "x"))
        assertEquals(2f, bottom(css, "x"))
    }

    @Test
    fun shorthandFourValuesTopRightBottomLeft() {
        // `margin: 1em 2em 3em 4em` -> top 1em, bottom 3em.
        val css = ".x { margin: 1em 2em 3em 4em }"
        assertEquals(1f, top(css, "x"))
        assertEquals(3f, bottom(css, "x"))
    }

    @Test
    fun longhandWinsOverShorthandBySourceOrder() {
        val css = ".x { margin: 1em; margin-top: 2em }"
        assertEquals(2f, top(css, "x"))
        assertEquals(1f, bottom(css, "x"))
    }

    @Test
    fun pxMarginConvertsToEmByBaseSize() {
        // px is normalized to em via the card base text size so the collapse/cap
        // logic is unit-uniform. 30px base -> 30px == 1em.
        val css = ".x { margin-top: 45px }"
        assertEquals(45f / CssMargins.BASE_PX, top(css, "x"))
    }

    @Test
    fun autoIsIgnored() {
        assertEquals(0f, top(".x { margin: auto }", "x"))
        assertEquals(0f, bottom(".x { margin: auto }", "x"))
    }

    @Test
    fun percentIsIgnored() {
        assertEquals(0f, top(".x { margin-top: 10% }", "x"))
    }

    @Test
    fun calcIsIgnored() {
        assertEquals(0f, top(".x { margin-top: calc(100% - 2em) }", "x"))
    }

    @Test
    fun cappedAtMax() {
        val css = ".x { margin-top: 99em }"
        assertEquals(CssMargins.MAX_EM, top(css, "x"))
    }

    @Test
    fun unknownClassHasNoMargin() {
        assertEquals(0f, top(".x { margin-top: 2em }", "other"))
    }

    @Test
    fun firstMatchingClassWins() {
        val css = ".a { margin-top: 2em } .b { margin-top: 1em }"
        assertEquals(2f, CssMargins.parse(css).topEmForClasses(listOf("a", "b")))
    }

    // --- shorthand forms not yet covered --------------------------------------

    @Test
    fun shorthandThreeValuesTopHorizontalBottom() {
        // `margin: 1em 2em 3em` -> top 1em, bottom 3em (the [2] slot), horizontal ignored.
        val css = ".x { margin: 1em 2em 3em }"
        assertEquals(1f, top(css, "x"))
        assertEquals(3f, bottom(css, "x"))
    }

    @Test
    fun shorthandWithMoreThanFourValuesRecordsNothing() {
        // Five values is invalid CSS; record nothing rather than guess which is `top`.
        val css = ".x { margin: 1em 2em 3em 4em 5em }"
        assertEquals(0f, top(css, "x"))
        assertEquals(0f, bottom(css, "x"))
    }

    @Test
    fun shorthandWithAutoVerticalRecordsNothingForThatSide() {
        // `margin: auto 1em` -> vertical is `auto`, unbounded, so nothing recorded.
        assertEquals(0f, top(".x { margin: auto 1em }", "x"))
        // `margin: 2em auto` (the classic centering idiom) still records the 2em vertical.
        assertEquals(2f, top(".x { margin: 2em auto }", "x"))
        assertEquals(2f, bottom(".x { margin: 2em auto }", "x"))
    }

    @Test
    fun shorthandFourValuesWithAutoBottomKeepsTopOnly() {
        val css = ".x { margin: 1em 0 auto 0 }"
        assertEquals(1f, top(css, "x"))
        assertEquals(0f, bottom(css, "x"))
    }

    // --- bounds ----------------------------------------------------------------

    @Test
    fun negativeMarginIsIgnored() {
        // Negative pull-ups aren't modelled by the UI's additive stacking, so a
        // negative length is treated as "not declared" rather than clamped to 0.
        assertEquals(0f, top(".x { margin-top: -2em }", "x"))
        assertEquals(0f, bottom(".x { margin-bottom: -15px }", "x"))
    }

    @Test
    fun negativeLonghandDoesNotOverrideEarlierShorthand() {
        // The negative longhand records nothing, so the shorthand's 1em survives.
        assertEquals(1f, top(".x { margin: 1em; margin-top: -3em }", "x"))
    }

    @Test
    fun zeroMarginIsRecordedAsZero() {
        // An explicit 0 must be honored (it can override an earlier shorthand).
        assertEquals(0f, top(".x { margin: 2em; margin-top: 0px }", "x"))
        assertEquals(2f, bottom(".x { margin: 2em; margin-top: 0px }", "x"))
    }

    @Test
    fun explicitPlusSignIsAccepted() {
        assertEquals(2f, top(".x { margin-top: +2em }", "x"))
    }

    @Test
    fun pxIsCappedAfterConversionToEm() {
        // 9000px / 30 = 300em, far over the cap.
        assertEquals(CssMargins.MAX_EM, top(".x { margin-top: 9000px }", "x"))
    }

    @Test
    fun unitIsCaseInsensitive() {
        assertEquals(2f, top(".x { margin-top: 2EM }", "x"))
        assertEquals(1f, top(".y { MARGIN-TOP: 30PX }", "y"))
    }

    @Test
    fun unitlessNonZeroIsIgnored() {
        // A bare number is not a valid margin length (and `0` alone is also unitless).
        assertEquals(0f, top(".x { margin-top: 2 }", "x"))
    }

    // --- selector shapes -------------------------------------------------------

    @Test
    fun tagDotClassSelectorRecordsTheClass() {
        // `div.tags` is honored via its class, matching CssHidden/CssTextAlign.
        assertEquals(2f, top("div.tags { margin-top: 2em }", "tags"))
    }

    @Test
    fun bareTagAndIdSelectorsRecordNothing() {
        // Only class-bearing simple selectors are honored — margins are applied
        // per-class by the UI, so a tag or id selector has nowhere to land.
        assertEquals(0f, top("div { margin-top: 2em }", "div"))
        assertEquals(0f, top("#main { margin-top: 2em }", "main"))
    }

    @Test
    fun descendantSelectorIsNotSimpleSoIgnored() {
        assertEquals(0f, top(".a .b { margin-top: 2em }", "b"))
    }

    @Test
    fun commentedOutMarginIsIgnored() {
        assertEquals(0f, top(".x { /* margin-top: 2em; */ }", "x"))
    }

    @Test
    fun multilineCommentBetweenRulesDoesNotSwallowThem() {
        val css = ".a { margin-top: 1em }\n/* a note\n   spanning lines */\n.b { margin-top: 2em }"
        assertEquals(1f, top(css, "a"))
        assertEquals(2f, top(css, "b"))
    }

    // --- at-rule stripping -----------------------------------------------------
    // Rules inside @media/@supports must not be read: they are conditional, and the
    // card renderer has no media context to evaluate them against. The brace-depth
    // scanner must also leave the surrounding rules intact.

    @Test
    fun marginInsideMediaQueryIsIgnored() {
        val css = "@media (max-width: 400px) { .x { margin-top: 2em } }"
        assertEquals(0f, top(css, "x"))
    }

    @Test
    fun rulesAroundAMediaBlockSurvive() {
        val css = """
            .before { margin-top: 1em }
            @media print { .inside { margin-top: 9em } }
            .after { margin-bottom: 2em }
        """.trimIndent()
        assertEquals(1f, top(css, "before"))
        assertEquals(0f, top(css, "inside"))
        assertEquals(2f, bottom(css, "after"))
    }

    @Test
    fun nestedAtRulesAreStrippedWhole() {
        // @supports wrapping @media: the depth scanner must consume both closing braces
        // and resume at the rule that follows.
        val css = """
            @supports (display: grid) { @media screen { .inner { margin-top: 9em } } }
            .after { margin-top: 1em }
        """.trimIndent()
        assertEquals(0f, top(css, "inner"))
        assertEquals(1f, top(css, "after"))
    }

    @Test
    fun statementAtRuleIsDroppedUpToSemicolon() {
        // `@import ...;` has no block; it is skipped to the `;` without eating the
        // following rule.
        val css = "@import url(\"other.css\"); .x { margin-top: 2em }"
        assertEquals(2f, top(css, "x"))
    }

    @Test
    fun unterminatedAtRuleDoesNotLoseTrailingInput() {
        // A truncated stylesheet must not hang or throw.
        assertEquals(0f, top("@media print { .x { margin-top: 2em }", "x"))
    }

    @Test
    fun cssWithNoMarginAtAllShortCircuitsToEmpty() {
        val m = CssMargins.parse(".x { color: red }")
        assertEquals(true, m.isEmpty)
        assertEquals(0f, m.topEmForClasses(listOf("x")))
    }

    @Test
    fun emptyCssIsEmpty() {
        assertEquals(true, CssMargins.parse("").isEmpty)
        assertEquals(true, CssMargins.EMPTY.isEmpty)
    }

    @Test
    fun aParsedMarginMakesItNonEmpty() {
        assertEquals(false, CssMargins.parse(".x { margin-top: 1em }").isEmpty)
    }
}
