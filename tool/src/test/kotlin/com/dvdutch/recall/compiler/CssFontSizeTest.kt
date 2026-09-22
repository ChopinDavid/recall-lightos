package com.dvdutch.recall.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bounded per-class `font-size` extraction and SCALE computation (Task 1). Only px
 * values and a single-level `var(--name)` resolved against `:root` are honored; `em`
 * is supported ONLY relative to the card base; `%`, `rem`, and keywords record
 * nothing. The SCALE = class px / card base px, clamped to [MIN_SCALE, MAX_SCALE].
 */
class CssFontSizeTest {

    private fun px(css: String, cls: String): Float? =
        CssFontSize.parse(css).pxForClasses(listOf(cls))

    @Test
    fun plainPxExtracted() {
        assertEquals(10f, px(".right-text { font-size: 10px }", "right-text"))
    }

    @Test
    fun varResolvesAgainstRoot() {
        val css = """
            :root { --font-size-tiny: 10px; }
            .right-text { font-size: var(--font-size-tiny); }
        """.trimIndent()
        assertEquals(10f, px(css, "right-text"))
    }

    @Test
    fun varWithoutRootIsIgnored() {
        assertNull(px(".x { font-size: var(--missing) }", "x"))
    }

    @Test
    fun percentIsIgnored() {
        assertNull(px(".x { font-size: 120% }", "x"))
    }

    @Test
    fun remIsIgnored() {
        assertNull(px(".x { font-size: 1.2rem }", "x"))
    }

    @Test
    fun keywordIsIgnored() {
        assertNull(px(".x { font-size: larger }", "x"))
    }

    @Test
    fun unknownClassHasNoSize() {
        assertNull(px(".x { font-size: 10px }", "other"))
    }

    @Test
    fun laterDeclarationWins() {
        assertEquals(14f, px(".x { font-size: 10px; font-size: 14px }", "x"))
    }

    @Test
    fun multiSelectorShares() {
        val css = ".a, .b { font-size: 14px }"
        assertEquals(14f, px(css, "a"))
        assertEquals(14f, px(css, "b"))
    }

    // --- card base + scale -----------------------------------------------------

    @Test
    fun cardBaseFromRootClassPx() {
        // The card root class (.prettify-flashcard) declares 28px -> that's the base.
        val css = """
            :root { --font-size-regular: 28px; }
            .prettify-flashcard { font-size: var(--font-size-regular); }
        """.trimIndent()
        assertEquals(28f, CssFontSize.parse(css).cardBasePx(listOf("prettify-flashcard")))
    }

    @Test
    fun cardBaseFallsBackToDefaultWhenUndeclared() {
        val css = ".right-text { font-size: 10px }"
        assertEquals(CssFontSize.DEFAULT_BASE_PX, CssFontSize.parse(css).cardBasePx(emptyList()))
    }

    @Test
    fun scaleIsClassPxOverBase() {
        val css = """
            :root { --font-size-regular: 28px; --font-size-tiny: 10px; }
            .prettify-flashcard { font-size: var(--font-size-regular); }
            .right-text { font-size: var(--font-size-tiny); }
        """.trimIndent()
        val f = CssFontSize.parse(css)
        val base = f.cardBasePx(listOf("prettify-flashcard"))
        // 10 / 28 = 0.357..., above MIN_SCALE 0.35 so it survives unclamped.
        assertEquals(10f / 28f, f.scaleForClasses(listOf("right-text"), base))
    }

    @Test
    fun scaleClampedToMin() {
        val css = """
            :root { --font-size-regular: 100px; }
            .prettify-flashcard { font-size: var(--font-size-regular); }
            .tiny { font-size: 5px }
        """.trimIndent()
        val f = CssFontSize.parse(css)
        val base = f.cardBasePx(listOf("prettify-flashcard"))
        assertEquals(CssFontSize.MIN_SCALE, f.scaleForClasses(listOf("tiny"), base))
    }

    @Test
    fun scaleClampedToMax() {
        val css = """
            :root { --font-size-regular: 10px; }
            .prettify-flashcard { font-size: var(--font-size-regular); }
            .huge { font-size: 100px }
        """.trimIndent()
        val f = CssFontSize.parse(css)
        val base = f.cardBasePx(listOf("prettify-flashcard"))
        assertEquals(CssFontSize.MAX_SCALE, f.scaleForClasses(listOf("huge"), base))
    }

    @Test
    fun unsizedClassHasScaleOfOne() {
        val css = ".prettify-flashcard { font-size: 28px } .word { }"
        val f = CssFontSize.parse(css)
        val base = f.cardBasePx(listOf("prettify-flashcard"))
        assertEquals(1f, f.scaleForClasses(listOf("word"), base))
    }

    // --- em relative to card base ---------------------------------------------

    @Test
    fun emIsRelativeToCardBase() {
        // font-size: 0.5em on a 28px base -> 14px effective, scale 0.5.
        val css = """
            .prettify-flashcard { font-size: 28px }
            .half { font-size: 0.5em }
        """.trimIndent()
        val f = CssFontSize.parse(css)
        val base = f.cardBasePx(listOf("prettify-flashcard"))
        assertEquals(0.5f, f.scaleForClasses(listOf("half"), base))
    }

    @Test
    fun nearestAncestorClassWins() {
        // pxForClasses returns the FIRST class in order that declares a size -> the
        // compiler passes nearest-ancestor-first so the nearest wins.
        val css = ".sentence { font-size: 18px } .flashcard { font-size: 28px }"
        assertEquals(18f, px(css, "sentence"))
        // when both present, first-listed (nearest) wins:
        assertEquals(18f, CssFontSize.parse(css).pxForClasses(listOf("sentence", "flashcard")))
    }

    // --- em is not an absolute px ---------------------------------------------

    @Test
    fun emIsNotAnAbsolutePx() {
        // An em size has no meaning without a base, so pxForClasses reports null —
        // it only participates through scaleForClasses.
        assertNull(px(".half { font-size: 0.5em }", "half"))
    }

    @Test
    fun anEmNearestSizeStopsTheSearchRatherThanFallingThrough() {
        // The nearest declared size is em, so pxForClasses returns null instead of
        // skipping past it to a farther ancestor's px — the nearest size still wins,
        // it just isn't absolute.
        val css = ".near { font-size: 0.5em } .far { font-size: 40px }"
        assertNull(CssFontSize.parse(css).pxForClasses(listOf("near", "far")))
    }

    @Test
    fun emOnTheRootClassFallsBackToTheDefaultBase() {
        // em on the card root has nothing to be relative to, so the base falls back.
        val css = ".prettify-flashcard { font-size: 2em }"
        assertEquals(
            CssFontSize.DEFAULT_BASE_PX,
            CssFontSize.parse(css).cardBasePx(listOf("prettify-flashcard")),
        )
    }

    @Test
    fun emScaleIsIndependentOfTheBaseValue() {
        // em * base / base == em, so a 0.75em class scales 0.75x on any base.
        val f = CssFontSize.parse(".x { font-size: 0.75em }")
        assertEquals(0.75f, f.scaleForClasses(listOf("x"), 28f))
        assertEquals(0.75f, f.scaleForClasses(listOf("x"), 40f))
    }

    @Test
    fun emScaleIsAlsoClamped() {
        val f = CssFontSize.parse(".huge { font-size: 9em }")
        assertEquals(CssFontSize.MAX_SCALE, f.scaleForClasses(listOf("huge"), 28f))
    }

    // --- degenerate base -------------------------------------------------------

    @Test
    fun nonPositiveBaseYieldsScaleOfOne() {
        // Dividing by a zero/negative base would produce infinity or a negative
        // scale; guard by returning the no-op scale.
        val f = CssFontSize.parse(".x { font-size: 14px }")
        assertEquals(1f, f.scaleForClasses(listOf("x"), 0f))
        assertEquals(1f, f.scaleForClasses(listOf("x"), -10f))
    }

    @Test
    fun scaleSkipsClassesWithNoDeclaredSize() {
        // The first class carries nothing; the search continues to the one that does.
        val f = CssFontSize.parse(".sized { font-size: 14px }")
        assertEquals(0.5f, f.scaleForClasses(listOf("unsized", "sized"), 28f))
    }

    @Test
    fun scaleOfOneWhenNothingIsDeclaredAtAll() {
        assertEquals(1f, CssFontSize.EMPTY.scaleForClasses(listOf("anything"), 28f))
    }

    // --- selector shapes -------------------------------------------------------

    @Test
    fun tagDotClassSelectorRecordsTheClass() {
        assertEquals(10f, px("span.right-text { font-size: 10px }", "right-text"))
    }

    @Test
    fun bareTagAndIdSelectorsRecordNothing() {
        assertNull(px("span { font-size: 10px }", "span"))
        assertNull(px("#main { font-size: 10px }", "main"))
    }

    @Test
    fun descendantSelectorIsNotSimpleSoIgnored() {
        assertNull(px(".a .b { font-size: 10px }", "b"))
    }

    @Test
    fun firstRuleWinsAcrossSeparateRules() {
        // Across rules the FIRST declaration for a class is kept (putIfAbsent), unlike
        // within one block where the last declaration wins.
        assertEquals(10f, px(".x { font-size: 10px } .x { font-size: 20px }", "x"))
    }

    @Test
    fun signedPxIsAccepted() {
        assertEquals(14f, px(".x { font-size: +14px }", "x"))
    }

    @Test
    fun unitIsCaseInsensitive() {
        assertEquals(14f, px(".x { font-size: 14PX }", "x"))
        assertEquals(0.5f, CssFontSize.parse(".x { font-size: 0.5EM }").scaleForClasses(listOf("x"), 28f))
    }

    @Test
    fun commentedOutFontSizeIsIgnored() {
        assertNull(px(".x { /* font-size: 10px */ }", "x"))
    }

    @Test
    fun aPropertyMerelyEndingInFontSizeIsNotRead() {
        // Per-declaration property matching keeps `-webkit-font-size` style vendor
        // properties (and anything else) from being mistaken for `font-size`.
        assertNull(px(".x { -webkit-font-size: 10px }", "x"))
    }

    // --- var() resolution ------------------------------------------------------

    @Test
    fun varWithFallbackStillResolvesFromRoot() {
        // `var(--name, 12px)` — the declared root value wins; the fallback text is
        // matched by the regex but not consulted.
        val css = """
            :root { --s: 18px; }
            .x { font-size: var(--s, 12px); }
        """.trimIndent()
        assertEquals(18f, px(css, "x"))
    }

    @Test
    fun varResolvingToAnEmIsTreatedAsEm() {
        val css = """
            :root { --s: 0.5em; }
            .x { font-size: var(--s); }
        """.trimIndent()
        val f = CssFontSize.parse(css)
        assertNull(f.pxForClasses(listOf("x")))
        assertEquals(0.5f, f.scaleForClasses(listOf("x"), 28f))
    }

    @Test
    fun varResolvingToAnUnsupportedUnitRecordsNothing() {
        val css = """
            :root { --s: 120%; }
            .x { font-size: var(--s); }
        """.trimIndent()
        assertNull(px(css, "x"))
    }

    @Test
    fun rootVarsAreReadFromAMultiSelectorRootRule() {
        // `:root, .card { --s: ... }` still contributes the variable.
        val css = """
            :root, .card { --s: 16px; }
            .x { font-size: var(--s); }
        """.trimIndent()
        assertEquals(16f, px(css, "x"))
    }

    @Test
    fun varsDeclaredOutsideRootAreNotUsed() {
        // Only `:root` is scanned for custom properties.
        val css = """
            .theme { --s: 16px; }
            .x { font-size: var(--s); }
        """.trimIndent()
        assertNull(px(css, "x"))
    }

    @Test
    fun firstRootDeclarationOfAVarWins() {
        val css = """
            :root { --s: 16px; --s: 22px; }
            .x { font-size: var(--s); }
        """.trimIndent()
        assertEquals(16f, px(css, "x"))
    }

    @Test
    fun anUnresolvableVarDoesNotDiscardAnEarlierGoodSize() {
        // The var declaration is skipped, leaving the preceding 12px as the winner.
        assertEquals(12f, px(".x { font-size: 12px; font-size: var(--missing) }", "x"))
    }

    // --- at-rule stripping -----------------------------------------------------

    @Test
    fun fontSizeInsideMediaQueryIsIgnored() {
        assertNull(px("@media (max-width: 400px) { .x { font-size: 40px } }", "x"))
    }

    @Test
    fun rulesAroundAMediaBlockSurvive() {
        val css = """
            .before { font-size: 12px }
            @media print { .inside { font-size: 99px } }
            .after { font-size: 20px }
        """.trimIndent()
        assertEquals(12f, px(css, "before"))
        assertNull(px(css, "inside"))
        assertEquals(20f, px(css, "after"))
    }

    @Test
    fun nestedAtRulesAreStrippedWhole() {
        val css = """
            @supports (display: grid) { @media screen { .inner { font-size: 99px } } }
            .after { font-size: 14px }
        """.trimIndent()
        assertNull(px(css, "inner"))
        assertEquals(14f, px(css, "after"))
    }

    @Test
    fun statementAtRuleIsDroppedUpToSemicolon() {
        assertEquals(14f, px("@import url(\"other.css\"); .x { font-size: 14px }", "x"))
    }

    @Test
    fun fontFaceBlockDoesNotContributeASize() {
        val css = """
            @font-face { font-family: Deck; src: url(d.woff2); font-size: 99px }
            .x { font-size: 14px }
        """.trimIndent()
        assertEquals(14f, px(css, "x"))
    }

    @Test
    fun unterminatedAtRuleDoesNotLoseTrailingInput() {
        assertNull(px("@media print { .x { font-size: 40px }", "x"))
    }

    // --- empty / short-circuit paths ------------------------------------------

    @Test
    fun cssWithNoFontSizeAtAllShortCircuitsToEmpty() {
        val f = CssFontSize.parse(".x { color: red }")
        assertEquals(true, f.isEmpty)
        assertNull(f.pxForClasses(listOf("x")))
    }

    @Test
    fun emptyCssIsEmpty() {
        assertEquals(true, CssFontSize.parse("").isEmpty)
        assertEquals(true, CssFontSize.EMPTY.isEmpty)
    }

    @Test
    fun aParsedSizeMakesItNonEmpty() {
        assertEquals(false, CssFontSize.parse(".x { font-size: 14px }").isEmpty)
    }
}
