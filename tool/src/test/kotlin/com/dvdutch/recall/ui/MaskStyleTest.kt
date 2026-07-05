package com.dvdutch.recall.ui

import androidx.compose.ui.graphics.Color
import com.dvdutch.recall.api.ShapeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [maskStyle], the single pure styling function that maps a resolved
 * [ShapeState] onto its fill/outline/border draw spec, across BOTH palettes.
 *
 * The DEFAULT palette is [MaskPalette.Anki] — it matches the exact colours AnkiDroid /
 * Anki desktop draw (inactive tan `#FFEBA2`, tested salmon `#FF8E8E`, revealed red
 * outline `#FF8E8E`), because our images render in full colour. The [MaskPalette.Mono]
 * palette is kept as a grayscale-only fallback (mid grey block + two-tone ring) for a
 * future device-colour toggle. Both must keep the load-bearing UX rule ("the tested mask
 * must be unmistakable among many inactive masks"), and the Anki fills must stay
 * distinguishable even if the device grayscales — asserted here via a relative-luminance
 * gap — so the palette is provably grayscale-safe without a device.
 */
class MaskStyleTest {

    // --- Default palette (Anki) ----------------------------------------------

    @Test
    fun `default palette is Anki`() {
        // maskStyle(state) with no palette arg must resolve identically to the Anki palette.
        for (state in ShapeState.entries) {
            assertEquals(
                maskStyle(state, MaskPalette.Anki),
                maskStyle(state),
                "default maskStyle($state) must equal the Anki palette",
            )
        }
    }

    @Test
    fun `context draws nothing (both palettes)`() {
        assertNull(maskStyle(ShapeState.CONTEXT, MaskPalette.Anki))
        assertNull(maskStyle(ShapeState.CONTEXT, MaskPalette.Mono))
    }

    // --- Anki palette per-state ----------------------------------------------

    @Test
    fun `anki inactive masked is a solid tan block`() {
        val s = maskStyle(ShapeState.MASKED, MaskPalette.Anki)
        assertNotNull(s)
        assertTrue(s.filled, "inactive mask must be a solid fill")
        assertEquals(Color(0xFFFFEBA2), s.fill, "Anki inactive fill is #FFEBA2")
    }

    @Test
    fun `anki tested masked is a solid salmon block (no two-tone ring)`() {
        val s = maskStyle(ShapeState.MASKED_TESTED, MaskPalette.Anki)
        assertNotNull(s)
        assertTrue(s.filled, "tested mask is still a solid fill (region stays hidden)")
        assertEquals(Color(0xFFFF8E8E), s.fill, "Anki tested fill is #FF8E8E")
        // AnkiDroid draws the SAME thin dark border on tested as on inactive — the
        // distinction is the fill colour, not a heavy ring. So no two-tone ring here.
        val border = s.border
        if (border != null) {
            assertEquals(border.outerColor, border.innerColor, "Anki tested border is single-tone")
        }
    }

    @Test
    fun `anki tested and inactive fills DIFFER (colour carries the distinction)`() {
        assertTrue(
            maskStyle(ShapeState.MASKED, MaskPalette.Anki)!!.fill !=
                maskStyle(ShapeState.MASKED_TESTED, MaskPalette.Anki)!!.fill,
            "Anki distinguishes tested from inactive by fill colour",
        )
    }

    @Test
    fun `anki revealed outline is stroke-only red`() {
        val s = maskStyle(ShapeState.REVEALED_OUTLINE, MaskPalette.Anki)
        assertNotNull(s)
        assertTrue(!s.filled, "revealed answer is an outline, not a fill")
        assertEquals(Color(0xFFFF8E8E), s.fill, "Anki revealed outline is red #FF8E8E")
    }

    // --- Mono palette per-state (preserved fallback) -------------------------

    @Test
    fun `mono inactive masked is a plain filled block with no distinguishing border`() {
        val s = maskStyle(ShapeState.MASKED, MaskPalette.Mono)
        assertNotNull(s)
        assertTrue(s.filled, "inactive mask must be a solid fill")
        assertNull(s.border, "inactive mask has no distinguishing border")
    }

    @Test
    fun `mono tested masked is a filled block WITH a heavy two-tone distinguishing border`() {
        val s = maskStyle(ShapeState.MASKED_TESTED, MaskPalette.Mono)
        assertNotNull(s)
        assertTrue(s.filled, "tested mask is still a solid fill (region stays hidden)")
        val border = s.border
        assertNotNull(border, "mono tested mask MUST carry a distinguishing border")
        assertTrue(border.outerWidthPx > 0f, "border must be visible")
        assertTrue(border.outerColor != border.innerColor, "ring must be two-tone for contrast")
    }

    @Test
    fun `mono tested and inactive fills are the same (both genuinely hide the region)`() {
        assertEquals(
            maskStyle(ShapeState.MASKED, MaskPalette.Mono)!!.fill,
            maskStyle(ShapeState.MASKED_TESTED, MaskPalette.Mono)!!.fill,
        )
    }

    @Test
    fun `mono revealed outline is stroke-only (not filled)`() {
        val s = maskStyle(ShapeState.REVEALED_OUTLINE, MaskPalette.Mono)
        assertNotNull(s)
        assertTrue(!s.filled, "revealed answer is an outline, not a fill")
    }

    // --- Grayscale-safety: luminance separation ------------------------------

    @Test
    fun `anki tested vs inactive fills have a healthy relative-luminance gap`() {
        // If the device grayscales, tested vs inactive must still be distinguishable by
        // brightness alone. Assert a healthy gap so the palette is provably grayscale-safe.
        val inactive = relativeLuminance(maskStyle(ShapeState.MASKED, MaskPalette.Anki)!!.fill)
        val tested = relativeLuminance(maskStyle(ShapeState.MASKED_TESTED, MaskPalette.Anki)!!.fill)
        val gap = kotlin.math.abs(inactive - tested)
        assertTrue(gap > 0.15, "Anki tested/inactive luminance gap must exceed 0.15, was $gap")
    }

    @Test
    fun `relativeLuminance matches the sRGB formula for known colours`() {
        // Black = 0, white = 1 anchors the WCAG relative-luminance computation.
        assertEquals(0.0, relativeLuminance(Color(0xFF000000)), 1e-6)
        assertEquals(1.0, relativeLuminance(Color(0xFFFFFFFF)), 1e-6)
    }
}
