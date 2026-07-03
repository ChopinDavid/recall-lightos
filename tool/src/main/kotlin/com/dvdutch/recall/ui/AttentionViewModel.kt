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

/**
 * Drives resolution of a needs-attention (FULL_*) divergence with a two-tap confirm:
 * the operator picks a direction, sees its concrete consequence (with the real local
 * card count), and confirms — no typed word. The confirm then runs the destructive
 * `fullSync(direction)`; on success it clears the controller's attention latch and the
 * screen goes back to Home. All phase transitions go through the pure [AttentionReducer].
 */
class AttentionViewModel(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val engine = RecallEngine(filesDir, dataStore)

    private val _uiState = MutableStateFlow(AttentionUiState())
    val uiState: StateFlow<AttentionUiState> = _uiState.asStateFlow()

    init {
        // Read this phone's card count up front so the confirm screen can state the
        // concrete consequence ("…deletes N cards…"). Best-effort: if the collection
        // can't be opened or counted, the count stays null and the copy omits the number.
        viewModelScope.launch(Dispatchers.IO) {
            val count = try {
                engine.openCollection()
                engine.localCardCount()
            } catch (_: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(localCardCount = count) }
            }
        }
    }

    /** Picked a direction — show its per-direction confirm (does not act yet). */
    fun choose(direction: AttentionDirection) {
        _uiState.update { AttentionReducer.choose(it, direction) }
    }

    /** Backed out of a confirm — return to the choice screen. */
    fun cancel() {
        _uiState.update { AttentionReducer.cancel(it) }
    }

    /**
     * Confirmed the destructive resolution for [direction]: run `fullSync(upload)`,
     * moving to Running while it is in flight and Done/Failed on the outcome.
     */
    fun confirm(direction: AttentionDirection) {
        _uiState.update { AttentionReducer.running(it, direction) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.openCollection()
                engine.controller().fullSync(upload = direction.upload)
                setState { AttentionReducer.done(it) }
            } catch (t: Throwable) {
                setState { AttentionReducer.failed(it, t.message ?: "sync failed") }
            }
        }
    }

    private suspend fun setState(transform: (AttentionUiState) -> AttentionUiState) {
        withContext(Dispatchers.Main) { _uiState.update(transform) }
    }
}
