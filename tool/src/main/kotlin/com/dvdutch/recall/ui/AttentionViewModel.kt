package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.engine.FullDownloadResult
import com.dvdutch.recall.engine.RecallEngine
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    // Test seams (default-args keep the production call sites unchanged): the engine
    // is injectable so the destructive divergence flows can be driven against a fake
    // controller, and the scope/dispatchers are overridable so tests run the coroutine
    // bodies deterministically without an Android Main dispatcher.
    private val engine: RecallEngine = RecallEngine(filesDir, dataStore),
    // Null in production → resolves to [viewModelScope] (can't be a constructor default,
    // as the instance isn't initialized yet); tests pass their own scope.
    injectedScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LightViewModel<Unit>() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _uiState = MutableStateFlow(AttentionUiState())
    val uiState: StateFlow<AttentionUiState> = _uiState.asStateFlow()

    init {
        // Read this phone's card count up front so the confirm screen can state the
        // concrete consequence ("…deletes N cards…"). Best-effort: if the collection
        // can't be opened or counted, the count stays null and the copy omits the number.
        scope.launch(ioDispatcher) {
            val count = try {
                engine.openCollection()
                engine.localCardCount()
            } catch (_: Throwable) {
                null
            }
            withContext(mainDispatcher) {
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
     * Confirmed the destructive resolution for [direction]. Upload runs the plain
     * `fullSync(upload=true)`. Download runs the GUARDED `fullDownload`: if the server turns
     * out to be EMPTY while this phone is populated, the transfer is rolled back and the flow
     * moves to [AttentionPhase.GuardConfirm] instead of silently wiping the phone — the user
     * must then explicitly [forceDownload] or CANCEL.
     */
    fun confirm(direction: AttentionDirection) {
        _uiState.update { AttentionReducer.running(it, direction) }
        scope.launch(ioDispatcher) {
            try {
                engine.openCollection()
                val controller = engine.controller()
                if (direction == AttentionDirection.Upload) {
                    controller.fullSync(upload = true)
                    setState { AttentionReducer.done(it) }
                } else {
                    when (val result = controller.fullDownload(force = false)) {
                        is FullDownloadResult.Downloaded -> setState { AttentionReducer.done(it) }
                        is FullDownloadResult.GuardTripped ->
                            setState { AttentionReducer.guardTripped(it, result.localCardCount) }
                        is FullDownloadResult.Failed ->
                            setState { AttentionReducer.failed(it, result.reason) }
                    }
                }
            } catch (t: Throwable) {
                setState { AttentionReducer.failed(it, t.message ?: "sync failed") }
            }
        }
    }

    /**
     * The user saw the empty-server guard and chose to erase the phone anyway: force the
     * download past the guard. Same outcome handling as [confirm]'s download, minus the
     * guard (it cannot trip a second time when forced).
     */
    fun forceDownload() {
        _uiState.update { AttentionReducer.running(it, AttentionDirection.Download) }
        scope.launch(ioDispatcher) {
            try {
                engine.openCollection()
                when (val result = engine.controller().fullDownload(force = true)) {
                    is FullDownloadResult.Downloaded -> setState { AttentionReducer.done(it) }
                    is FullDownloadResult.Failed -> setState { AttentionReducer.failed(it, result.reason) }
                    is FullDownloadResult.GuardTripped -> // unreachable when forced; be safe
                        setState { AttentionReducer.done(it) }
                }
            } catch (t: Throwable) {
                setState { AttentionReducer.failed(it, t.message ?: "sync failed") }
            }
        }
    }

    private suspend fun setState(transform: (AttentionUiState) -> AttentionUiState) {
        withContext(mainDispatcher) { _uiState.update(transform) }
    }
}
