package com.dvdutch.recall.ui

import com.dvdutch.recall.api.ShapeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [maskStyle], the single pure styling function that maps a resolved
 * [ShapeState] onto its fill/outline/border draw spec. Centralising this here is what
 * lets a future COLOR mode (pink tested / tan inactive, AnkiDroid-style) be added by
 * swapping ONE function — and it makes the load-bearing UX rule ("the tested mask must
 * be unmistakable among many inactive masks") unit-testable without a device.
 */
class MaskStyleTest {

    @Test
    fun `context draws nothing`() {
        assertNull(maskStyle(ShapeState.CONTEXT))
    }

    @Test
    fun `inactive masked is a plain filled block with no distinguishing border`() {
        val s = maskStyle(ShapeState.MASKED)
        assertNotNull(s)
        assertTrue(s.filled, "inactive mask must be a solid fill")
        assertNull(s.border, "inactive mask has no distinguishing border")
    }

    @Test
    fun `tested masked is a filled block WITH a heavy distinguishing border`() {
        val s = maskStyle(ShapeState.MASKED_TESTED)
        assertNotNull(s)
        assertTrue(s.filled, "tested mask is still a solid fill (region stays hidden)")
        val border = s.border
        assertNotNull(border, "tested mask MUST carry a distinguishing border")
        assertTrue(border.outerWidthPx > 0f, "border must be visible")
        // A two-tone ring (contrasting outer/inner colours) so it pops on both light and
        // dark image regions in monochrome.
        assertTrue(border.outerColor != border.innerColor, "ring must be two-tone for contrast")
    }

    @Test
    fun `tested and inactive fills are the same (both genuinely hide the region)`() {
        // Distinction is carried by the border, not by making the tested fill translucent
        // — the answer must stay covered on the front.
        assertEquals(maskStyle(ShapeState.MASKED)!!.fill, maskStyle(ShapeState.MASKED_TESTED)!!.fill)
    }

    @Test
    fun `revealed outline is stroke-only (not filled)`() {
        val s = maskStyle(ShapeState.REVEALED_OUTLINE)
        assertNotNull(s)
        assertTrue(!s.filled, "revealed answer is an outline, not a fill")
    }
}
