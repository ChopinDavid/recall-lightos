package com.dvdutch.recall.ui

/**
 * A direction for resolving a needs-attention (FULL_*) divergence via a full,
 * one-way collection transfer. [upload] is the argument passed straight to
 * `SyncController.fullSync(upload)`: Download pulls the server's copy down
 * (overwriting this phone); Upload pushes this phone's copy up (overwriting the
 * server). Both are destructive on the side being replaced.
 */
enum class AttentionDirection(val upload: Boolean) {
    /** Pull the server's collection down, discarding local divergence. */
    Download(upload = false),

    /** Push the local collection up, discarding the server's divergence. */
    Upload(upload = true),
}

/**
 * The phase of the needs-attention resolution flow. Resolving a FULL_* divergence
 * overwrites one side's collection, so confirmation is a deliberate two-tap — pick a
 * direction on [Choose], then confirm the concrete consequence on [Confirm] — instead
 * of the old typed-word gate. This mirrors AnkiDroid/desktop's choice + confirm dialog
 * and removes all typing from the screen users hit while confused.
 */
sealed interface AttentionPhase {
    /** Explaining the divergence; both direction choices are shown. */
    data object Choose : AttentionPhase

    /**
     * The operator picked [direction] and sees its concrete consequence, stated with the
     * real [localCardCount] where known (null when the count couldn't be read cheaply).
     * Two taps in: confirm runs the destructive sync, cancel returns to [Choose].
     */
    data class Confirm(val direction: AttentionDirection, val localCardCount: Int?) : AttentionPhase

    /** A `fullSync(direction)` is in flight. */
    data class Running(val direction: AttentionDirection) : AttentionPhase

    /** Resolved: the divergence is cleared; the screen goes back. */
    data object Done : AttentionPhase

    /** The full sync failed; [reason] is shown with a way back. */
    data class Failed(val reason: String) : AttentionPhase
}

/**
 * The needs-attention UI state: the current [phase] plus the [localCardCount] this
 * phone holds (null until read from the engine, or if reading it failed). The count is
 * held on the state — not just the Confirm phase — so it survives a cancel back to
 * [Choose] and doesn't need re-reading.
 */
data class AttentionUiState(
    val phase: AttentionPhase = AttentionPhase.Choose,
    val localCardCount: Int? = null,
)

/**
 * Pure phase transitions for the two-tap resolution flow. Kept separate from the
 * ViewModel so the whole confirm-flow shape is unit-testable without a backend: pick →
 * confirm-or-cancel → run → done-or-failed. There is no typed-word matcher any more —
 * confirmation is the deliberate two-tap itself.
 */
object AttentionReducer {

    /** Enter the per-direction confirm, carrying the known local card count. */
    fun choose(state: AttentionUiState, direction: AttentionDirection): AttentionUiState =
        state.copy(phase = AttentionPhase.Confirm(direction, state.localCardCount))

    /** Back out of a confirm to the choice screen (card count is retained). */
    fun cancel(state: AttentionUiState): AttentionUiState =
        state.copy(phase = AttentionPhase.Choose)

    /** The destructive sync is now in flight for [direction]. */
    fun running(state: AttentionUiState, direction: AttentionDirection): AttentionUiState =
        state.copy(phase = AttentionPhase.Running(direction))

    /** The sync resolved the divergence. */
    fun done(state: AttentionUiState): AttentionUiState =
        state.copy(phase = AttentionPhase.Done)

    /** The sync failed with [reason]. */
    fun failed(state: AttentionUiState, reason: String): AttentionUiState =
        state.copy(phase = AttentionPhase.Failed(reason))
}
