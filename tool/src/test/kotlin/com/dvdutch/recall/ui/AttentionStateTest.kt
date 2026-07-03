package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure tests for the needs-attention resolution flow. Resolving a FULL_* divergence
 * is destructive (one side's collection is overwritten), so the operator confirms
 * with a deliberate two-tap: pick a direction on the Choose screen, then confirm the
 * concrete consequence on a per-direction screen — no typed word. These tests cover
 * the pure [AttentionDirection] mapping and the [AttentionPhase] transitions produced
 * by [AttentionReducer]; the actual `fullSync` is exercised by the ViewModel.
 */
class AttentionStateTest {

    @Test
    fun `direction upload flag maps to fullSync argument`() {
        assertTrue(AttentionDirection.Upload.upload)
        assertFalse(AttentionDirection.Download.upload)
    }

    @Test
    fun `flow starts on the choose phase`() {
        assertIs<AttentionPhase.Choose>(AttentionUiState().phase)
    }

    @Test
    fun `choosing download opens the download confirm carrying the local card count`() {
        val state = AttentionReducer.choose(
            AttentionUiState(localCardCount = 42),
            AttentionDirection.Download,
        )
        val phase = assertIs<AttentionPhase.Confirm>(state.phase)
        assertEquals(AttentionDirection.Download, phase.direction)
        assertEquals(42, phase.localCardCount)
    }

    @Test
    fun `choosing upload opens the upload confirm carrying the local card count`() {
        val state = AttentionReducer.choose(
            AttentionUiState(localCardCount = 7),
            AttentionDirection.Upload,
        )
        val phase = assertIs<AttentionPhase.Confirm>(state.phase)
        assertEquals(AttentionDirection.Upload, phase.direction)
        assertEquals(7, phase.localCardCount)
    }

    @Test
    fun `an unknown local card count carries through as null`() {
        val state = AttentionReducer.choose(AttentionUiState(), AttentionDirection.Download)
        val phase = assertIs<AttentionPhase.Confirm>(state.phase)
        assertEquals(null, phase.localCardCount)
    }

    @Test
    fun `cancelling a confirm returns to choose without losing the card count`() {
        val confirming = AttentionReducer.choose(
            AttentionUiState(localCardCount = 99),
            AttentionDirection.Upload,
        )
        val state = AttentionReducer.cancel(confirming)
        assertIs<AttentionPhase.Choose>(state.phase)
        assertEquals(99, state.localCardCount)
    }

    @Test
    fun `confirming moves to running for the chosen direction`() {
        val state = AttentionReducer.running(AttentionUiState(), AttentionDirection.Upload)
        val phase = assertIs<AttentionPhase.Running>(state.phase)
        assertEquals(AttentionDirection.Upload, phase.direction)
    }

    @Test
    fun `success moves to done`() {
        assertIs<AttentionPhase.Done>(AttentionReducer.done(AttentionUiState()).phase)
    }

    @Test
    fun `failure carries its reason`() {
        val phase = assertIs<AttentionPhase.Failed>(
            AttentionReducer.failed(AttentionUiState(), "boom").phase,
        )
        assertEquals("boom", phase.reason)
    }
}
