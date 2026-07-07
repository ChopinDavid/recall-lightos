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
}
