package com.dvdutch.recall.ui

import com.dvdutch.recall.api.ShapeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure coverage for the "Toggle Masks" control (AnkiDroid parity): a UI-only peek that
 * temporarily hides EVERY occlusion mask so the studier can see the full image context,
 * then restores the exact prior rendering.
 *
 * The whole toggle is achieved by mapping each shape's resolved [ShapeState] through
 * [effectiveShapeState] before it reaches the render styler: masks-off collapses every
 * state to [ShapeState.CONTEXT] (draw nothing → full image), while masks-on passes the
 * state through UNCHANGED so restore is bit-for-bit identical to normal rendering. The
 * OcclusionImage draw path itself is not otherwise touched — masks-off simply feeds
 * CONTEXT states, exactly as the design requires.
 */
class OcclusionMaskToggleTest {

    @Test
    fun masksHidden_collapsesEveryStateToContext() {
        // Every resolved state — including the tested mask and the revealed outline — must
        // render as CONTEXT (nothing drawn) while masks are hidden, so the full image shows.
        for (state in ShapeState.entries) {
            assertEquals(
                ShapeState.CONTEXT,
                effectiveShapeState(state, masksHidden = true),
                "masks-off must render $state as CONTEXT (draw nothing)",
            )
            // …and CONTEXT means maskStyle draws nothing.
            assertNull(
                maskStyle(effectiveShapeState(state, masksHidden = true)),
                "masks-off $state must produce no draw style",
            )
        }
    }

    @Test
    fun masksShown_passesEveryStateThroughUnchanged() {
        // Restore is exact: with masks shown, the state — and therefore its draw style — is
        // identical to normal rendering for every state.
        for (state in ShapeState.entries) {
            assertEquals(
                state,
                effectiveShapeState(state, masksHidden = false),
                "masks-on must leave $state unchanged",
            )
            assertEquals(
                maskStyle(state),
                maskStyle(effectiveShapeState(state, masksHidden = false)),
                "masks-on $state must produce the exact normal draw style",
            )
        }
    }
}
