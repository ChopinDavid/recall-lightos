package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.engine.RecallEngine
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the deck-list screen is currently showing. */
sealed interface HomeMode {
    /** Prefs not yet read, or a decks load is in flight. */
    data object Loading : HomeMode

    /** No collection downloaded yet — the screen navigates to first-run. */
    data object NeedsFirstRun : HomeMode

    /** The collection has a FULL_* divergence — the screen navigates to attention. */
    data object NeedsAttention : HomeMode

    /** Decks loaded; [rows] is the flattened, indented tree. */
    data class Loaded(val rows: List<DeckRow>) : HomeMode

    /** A decks load failed; [message] is shown with a retry. */
    data class Error(val message: String) : HomeMode

    companion object {
        /**
         * Pure routing decision, extracted so it is JVM-testable without the Android
         * [LightViewModel] runtime. When a FULL_* divergence has latched — either durably
         * (the persisted pref, which survives the session/process that latched it) or on the
         * live controller flow — the answer is [NeedsAttention]; otherwise the caller loads
         * the deck tree. First-run is handled earlier (no collection file).
         */
        fun attentionRoute(configured: Boolean, needsAttention: Boolean): Boolean =
            configured && needsAttention
    }
}

data class HomeUiState(val mode: HomeMode = HomeMode.Loading)

/**
 * ViewModel for [RecallHomeScreen]. On show it decides between three routes off the
 * on-device engine ([RecallEngine]):
 *   - no collection file yet         → [HomeMode.NeedsFirstRun] (bounce to first-run);
 *   - collection present, configured → open it, and if the controller latched a
 *     FULL_* divergence → [HomeMode.NeedsAttention]; otherwise load the deck tree;
 *   - any failure                    → [HomeMode.Error] with a retry.
 */
class RecallHomeViewModel(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val engine = RecallEngine(filesDir, dataStore)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Re-load whenever the screen becomes visible (e.g. returning from first-run). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        load()
    }

    fun load() {
        _uiState.update { it.copy(mode = HomeMode.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!engine.storage.collectionExists()) {
                setMode(HomeMode.NeedsFirstRun)
                return@launch
            }
            val mode = try {
                engine.openCollection()
                val controller = engine.controller()
                // Durable pref OR the live flow: the pref catches a divergence that latched
                // in a prior session (the controller instance that latched it is long gone),
                // the flow catches one that latches during this process's lifetime.
                val diverged = engine.needsAttention() || controller.needsAttention.value
                if (HomeMode.attentionRoute(controller.configured, diverged)) {
                    HomeMode.NeedsAttention
                } else {
                    HomeMode.Loaded(deckRows(engine.api(controller).decks()))
                }
            } catch (t: Throwable) {
                HomeMode.Error(t.message ?: "couldn't open your collection")
            }
            setMode(mode)
        }
    }

    private suspend fun setMode(mode: HomeMode) {
        withContext(Dispatchers.Main) { _uiState.update { it.copy(mode = mode) } }
    }
}
