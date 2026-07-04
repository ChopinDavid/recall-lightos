package com.dvdutch.recall.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [revealScrollTarget], the pure decision behind the tall-card
 * auto-scroll. On a card whose answer starts below the fold, tapping REVEAL used to
 * append the answer under the already-tall front content, landing it off-screen; the
 * user had to notice and scroll manually every reveal. This function decides, from the
 * measured divider position, whether to scroll and to what offset — mirroring
 * AnkiDroid keeping the question/answer boundary in view.
 *
 * The contract: return null (do NOT scroll — no motion for the common, fits-on-screen
 * case) when the divider is already visible in the viewport at the current scroll; else
 * return the scroll offset that places the divider a small margin below the viewport top,
 * clamped to the valid range so we never over-scroll past the content.
 */
class RevealScrollTargetTest {

    // A representative viewport height and top margin in px for these cases.
    private val viewportH = 1000
    private val margin = 80

    @Test
    fun dividerAlreadyOnScreenReturnsNull() {
        // Divider at y=400 with the view scrolled to 0: it sits within [0, 1000) → visible.
        assertNull(
            revealScrollTarget(
                dividerY = 400,
                viewportH = viewportH,
                currentScroll = 0,
                maxScroll = 5000,
                topMargin = margin,
            ),
        )
    }

    @Test
    fun dividerAtViewportTopEdgeIsVisibleReturnsNull() {
        // Exactly at the current scroll position (top of viewport) — already visible.
        assertNull(
            revealScrollTarget(
                dividerY = 300,
                viewportH = viewportH,
                currentScroll = 300,
                maxScroll = 5000,
                topMargin = margin,
            ),
        )
    }

    @Test
    fun dividerBelowFoldScrollsSoItSitsBelowTopMargin() {
        // Divider at y=2000, viewport currently [0,1000): 2000 >= 1000 → below fold.
        // Target places it topMargin below the viewport top: 2000 - 80 = 1920.
        assertEquals(
            1920,
            revealScrollTarget(
                dividerY = 2000,
                viewportH = viewportH,
                currentScroll = 0,
                maxScroll = 5000,
                topMargin = margin,
            ),
        )
    }

    @Test
    fun dividerJustBelowBottomEdgeScrolls() {
        // Divider at exactly viewport bottom (y=1000 with scroll 0): not visible (bottom is
        // exclusive), so we scroll it up to the margin: 1000 - 80 = 920.
        assertEquals(
            920,
            revealScrollTarget(
                dividerY = 1000,
                viewportH = viewportH,
                currentScroll = 0,
                maxScroll = 5000,
                topMargin = margin,
            ),
        )
    }

    @Test
    fun targetClampedToMaxScrollNearEnd() {
        // Divider near the very end: naive target 4980-80 = 4900 exceeds maxScroll 4200,
        // so it clamps to 4200 (we can't scroll past the content's end).
        assertEquals(
            4200,
            revealScrollTarget(
                dividerY = 4980,
                viewportH = viewportH,
                currentScroll = 0,
                maxScroll = 4200,
                topMargin = margin,
            ),
        )
    }

    @Test
    fun targetClampedToZeroWhenMarginExceedsDivider() {
        // A divider below the fold but with a large margin could compute a negative offset;
        // clamp to >= 0. Here viewport is tiny so the divider is below fold, but dividerY <
        // margin would push the offset negative.
        assertEquals(
            0,
            revealScrollTarget(
                dividerY = 50,
                viewportH = 40,
                currentScroll = 0,
                maxScroll = 5000,
                topMargin = margin,
            ),
        )
    }
}
