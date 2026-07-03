package com.dvdutch.recall.ui

import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.scrollBarGutterUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the occlusion answer-side flicker: an infinite show/hide layout
 * loop in [com.thelightphone.sdk.ui.LightScrollView]. The loop existed because the
 * scrollbar gutter was reserved only WHEN the bar was shown, so toggling the bar changed
 * the content width, which (for `fillMaxWidth().aspectRatio()` content like an occlusion
 * image) changed the content height, which flipped viewport overflow, which toggled the
 * bar again — forever.
 *
 * The fix makes the reserved gutter a pure function of the scrollbar POSITION alone. These
 * tests pin that invariant: the gutter must not depend on any overflow / bar-visibility
 * signal (the function takes no such parameter), and the Outside position must reserve a
 * positive, constant gutter so content width is stable regardless of bar visibility.
 */
class ScrollBarGutterTest {

    @Test
    fun outsideReservesAConstantPositiveGutter() {
        val gutter = scrollBarGutterUnits(LightScrollBarPosition.Outside)
        assertTrue("Outside must reserve a positive gutter so width is stable", gutter > 0f)
        // Called repeatedly it must return the SAME value — it depends on nothing but position.
        assertEquals(gutter, scrollBarGutterUnits(LightScrollBarPosition.Outside), 0f)
    }

    @Test
    fun insideReservesNoGutter() {
        // Inside overlays the bar, so no horizontal space is taken from content.
        assertEquals(0f, scrollBarGutterUnits(LightScrollBarPosition.Inside), 0f)
    }
}
