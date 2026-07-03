package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.engine.RecallEngine
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The phase of the needs-attention resolution flow. */
sealed interface AttentionPhase {
    /** Explaining the divergence; both direction buttons are shown. */
    data object Explain : AttentionPhase

    /** The operator picked [direction] and must type its confirmation word. */
    data class Confirming(val direction: AttentionDirection) : AttentionPhase

    /** A `fullSync(direction)` is in flight. */
    data class Resolving(val direction: AttentionDirection) : AttentionPhase

    /** Resolved: the divergence is cleared; the screen goes back. */
    data object Resolved : AttentionPhase

    /** The full sync failed; [reason] is shown with a retry. */
    data class Failed(val reason: String) : AttentionPhase
}

data class AttentionUiState(val phase: AttentionPhase = AttentionPhase.Explain)

/**
 * Drives resolution of a needs-attention (FULL_*) divergence: pick a direction,
 * type the exact confirmation word ([AttentionConfirm]), then run the destructive
 * `fullSync(direction)`. On success it clears the controller's attention latch and
 * the screen goes back to Home.
 */
class AttentionViewModel(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val engine = RecallEngine(filesDir, dataStore)

    private val _uiState = MutableStateFlow(AttentionUiState())
    val uiState: StateFlow<AttentionUiState> = _uiState.asStateFlow()

    fun choose(direction: AttentionDirection) {
        _uiState.update { it.copy(phase = AttentionPhase.Confirming(direction)) }
    }

    fun cancel() {
        _uiState.update { it.copy(phase = AttentionPhase.Explain) }
    }

    /**
     * Called with the typed confirmation. Runs the full sync only when [typed] is the
     * exact direction word; otherwise stays on the confirm phase (the screen re-prompts).
     */
    fun confirm(direction: AttentionDirection, typed: CharSequence) {
        if (!AttentionConfirm.matches(direction, typed.toString())) {
            _uiState.update { it.copy(phase = AttentionPhase.Confirming(direction)) }
            return
        }
        _uiState.update { it.copy(phase = AttentionPhase.Resolving(direction)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.openCollection()
                engine.controller().fullSync(upload = direction.upload)
                setPhase(AttentionPhase.Resolved)
            } catch (t: Throwable) {
                setPhase(AttentionPhase.Failed(t.message ?: "sync failed"))
            }
        }
    }

    private suspend fun setPhase(phase: AttentionPhase) {
        withContext(Dispatchers.Main) { _uiState.update { it.copy(phase = phase) } }
    }
}
