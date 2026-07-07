package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.BlockAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [CssTextAlign]: per-class `text-align` extraction (same simple
 * class-selector discipline as [CssHidden]) plus `var(--name)` resolution against
 * a single `:root { --name: value }` block, and `display: flex` class detection.
 */
class CssTextAlignTest {

    private fun align(css: String, cls: String): BlockAlign? =
        CssTextAlign.parse(css).alignForClasses(listOf(cls))

    @Test
    fun plainCenterClass() {
        assertEquals(BlockAlign.CENTER, align(".center-text { text-align: center }", "center-text"))
    }

    @Test
    fun plainEndFromRight() {
        assertEquals(BlockAlign.END, align(".right-text { text-align: right }", "right-text"))
    }

    @Test
    fun leftIsStart() {
        assertEquals(BlockAlign.START, align(".left-text { text-align: left }", "left-text"))
    }

    @Test
    fun unknownClassHasNoAlign() {
        assertNull(align(".center-text { text-align: center }", "other"))
    }

    @Test
    fun varResolvesAgainstRoot() {
        val css = """
            :root { --card-text-align: center; }
            .prettify-flashcard { text-align: var(--card-text-align); }
        """.trimIndent()
        assertEquals(BlockAlign.CENTER, align(css, "prettify-flashcard"))
    }

    @Test
    fun varWithoutRootDefinitionIsIgnored() {
        // No :root definition -> cannot resolve -> no alignment recorded.
        assertNull(align(".x { text-align: var(--missing) }", "x"))
    }

    @Test
    fun varResolvingToLeftIsStart() {
        val css = """
            :root { --a: left; }
            .x { text-align: var(--a); }
        """.trimIndent()
        assertEquals(BlockAlign.START, align(css, "x"))
    }

    @Test
    fun multiSelectorSharesAlign() {
        val css = ".a, .b { text-align: center }"
        assertEquals(BlockAlign.CENTER, align(css, "a"))
        assertEquals(BlockAlign.CENTER, align(css, "b"))
    }

    @Test
    fun justifyMapsToStart() {
        // Only center/right(end) change layout for us; justify stays start.
        assertEquals(BlockAlign.START, align(".x { text-align: justify }", "x"))
    }

    // --- display: flex detection ---------------------------------------------

    @Test
    fun flexRowClassDetected() {
        val css = ".header-container { display: flex; justify-content: space-between }"
        assertEquals(true, CssTextAlign.parse(css).isFlexRow(listOf("header-container")))
    }

    @Test
    fun flexColumnNotRow() {
        val css = ".c { display: flex; flex-direction: column }"
        assertEquals(false, CssTextAlign.parse(css).isFlexRow(listOf("c")))
    }

    @Test
    fun flexRowExplicitDirectionIsRow() {
        val css = ".c { display: flex; flex-direction: row }"
        assertEquals(true, CssTextAlign.parse(css).isFlexRow(listOf("c")))
    }

    @Test
    fun nonFlexClassNotRow() {
        val css = ".c { display: block }"
        assertEquals(false, CssTextAlign.parse(css).isFlexRow(listOf("c")))
    }

    @Test
    fun displayCenterIsNotFlex() {
        // `.prettify-tags { display: center }` is invalid CSS, never a flex row.
        assertEquals(false, CssTextAlign.parse(css = ".prettify-tags { display: center }").isFlexRow(listOf("prettify-tags")))
    }
}
