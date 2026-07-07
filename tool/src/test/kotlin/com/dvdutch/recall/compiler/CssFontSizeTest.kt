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
}
