package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.api.Deck
import com.dvdutch.recall.engine.RecallEngine
import com.dvdutch.recall.prefs.RecallPreferences
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * The manual-sync (top-bar 🔄) control's state, orthogonal to [HomeMode]: a sync runs over
 * whatever the deck list is currently showing and only mutates it on completion.
 */
sealed interface SyncState {
    /** No manual sync running; the icon renders solid and is tappable. */
    data object Idle : SyncState

    /** A manual sync is in flight; the icon renders ghosted and ignores taps. */
    data object InFlight : SyncState

    /** The last manual sync failed; [message] is the single lightened line above the list. */
    data class Failed(val message: String) : SyncState
}

data class HomeUiState(
    val mode: HomeMode = HomeMode.Loading,
    val syncState: SyncState = SyncState.Idle,
)

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
    // Test seams (default-args keep production call sites unchanged): inject the engine
    // to drive the load routing against a fake, and the scope/dispatchers so the load
    // and toggle coroutines run deterministically without an Android Main dispatcher.
    private val engine: RecallEngine = RecallEngine(filesDir, dataStore),
    // Null in production → resolves to [viewModelScope]; tests pass their own scope.
    injectedScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LightViewModel<Unit>() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * The most recent flat deck list, cached so [toggle] can re-filter locally without
     * reopening the collection (no collection read/write happens on a collapse toggle).
     */
    private var lastDecks: List<Deck> = emptyList()

    /** Re-load whenever the screen becomes visible (e.g. returning from first-run). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        load()
    }

    fun load() {
        // A fresh load (screen show / retry / return from another screen) clears any stale
        // manual-sync error line — but never interrupts an in-flight manual sync's ghosting.
        _uiState.update {
            val clearedSync = if (it.syncState is SyncState.Failed) SyncState.Idle else it.syncState
            it.copy(mode = HomeMode.Loading, syncState = clearedSync)
        }
        scope.launch(ioDispatcher) {
            if (!engine.storage.collectionExists()) {
                setMode(HomeMode.NeedsFirstRun)
                return@launch
            }
            val mode = try {
                engine.openCollection()
                val controller = engine.controller()
                // The open + controller read are not redundant with the durable pref: routing
                // gates on controller.configured (below) and we need the open collection to read
                // decks in the non-diverged case anyway. Divergence itself is the durable pref OR
                // the live flow — the pref catches a divergence latched in a prior session (that
                // controller instance is long gone), the flow catches one latched this process.
                val diverged = engine.needsAttention() || controller.needsAttention.value
                if (HomeMode.attentionRoute(controller.configured, diverged)) {
                    HomeMode.NeedsAttention
                } else {
                    val decks = engine.decks(controller)
                    lastDecks = decks
                    HomeMode.Loaded(visibleDeckRows(decks, readExpandedIds()))
                }
            } catch (t: Throwable) {
                HomeMode.Error(t.message ?: "couldn't open your collection")
            }
            setMode(mode)
        }
    }

    /**
     * The manual-sync control (the top-bar 🔄). Runs the SAME normal collection sync the
     * session boundaries use ([SyncController.sync], media included) and reflects the outcome:
     *   - while in flight             → [SyncState.InFlight] (icon ghosted; a second tap during
     *     this window is a no-op — the guard below drops it, so no double-fire / no race);
     *   - a FULL_* divergence latched → [HomeMode.NeedsAttention] (routes to AttentionScreen,
     *     exactly like session-start sync), clearing any prior error;
     *   - a clean sync                → clear the error line and RELOAD the deck tree (new
     *     decks / updated counts appear) without leaving Home;
     *   - a non-fatal failure         → [SyncState.Failed] with a lightened line above the list,
     *     cleared on the next clean sync (or on the next [load]/navigation).
     *
     * Concurrency: [SyncController.sync] confines every backend touch to the single serial
     * [com.dvdutch.recall.engine.EngineHolder.lane] shared by PeriodicSync and session sync, so
     * a manual sync can never enter the backend re-entrantly with them; the in-flight guard here
     * additionally prevents a rapid double-tap from queuing a redundant second sync.
     */
    fun sync() {
        // No-op if a manual sync is already running (icon is ghosted): no second sync call,
        // no double-fire on rapid taps.
        if (_uiState.value.syncState == SyncState.InFlight) return
        _uiState.update { it.copy(syncState = SyncState.InFlight) }
        scope.launch(ioDispatcher) {
            try {
                engine.openCollection()
                val controller = engine.controller()
                if (!controller.configured) {
                    setSyncState(SyncState.Failed("sync not configured — check settings"))
                    return@launch
                }
                val info = controller.sync(media = true)
                // A FULL_* divergence just latched → route to attention like session-start does.
                if (engine.needsAttention() || controller.needsAttention.value) {
                    setSyncState(SyncState.Idle)
                    setMode(HomeMode.NeedsAttention)
                    return@launch
                }
                if (info.synced) {
                    // Refresh the deck tree over the freshly synced collection, then clear
                    // any prior error and drop out of in-flight.
                    val decks = engine.decks(controller)
                    lastDecks = decks
                    setMode(HomeMode.Loaded(visibleDeckRows(decks, readExpandedIds())))
                    setSyncState(SyncState.Idle)
                } else {
                    setSyncState(SyncState.Failed("sync failed — check settings"))
                }
            } catch (t: Throwable) {
                setSyncState(SyncState.Failed("sync failed — check settings"))
            }
        }
    }

    /**
     * Toggle the local collapse state of a parent deck: expand it if collapsed (add its id
     * to the persisted EXPANDED set) or collapse it otherwise (remove it). Persists to
     * DataStore and re-renders the visible tree reactively — this touches only the UI
     * preference, never the Anki collection.
     */
    fun toggle(deckId: Long) {
        scope.launch(ioDispatcher) {
            val current = readExpandedIds()
            val next = if (deckId in current) current - deckId else current + deckId
            dataStore.edit { prefs ->
                prefs[RecallPreferences.EXPANDED_DECK_IDS] = next.map { it.toString() }.toSet()
            }
            // Re-filter from the cached deck list; no collection reopen needed.
            if (_uiState.value.mode is HomeMode.Loaded) {
                setMode(HomeMode.Loaded(visibleDeckRows(lastDecks, next)))
            }
        }
    }

    /** Read the persisted EXPANDED-id set (decimal strings) as longs; absent ⇒ empty. */
    private suspend fun readExpandedIds(): Set<Long> =
        dataStore.data.first()[RecallPreferences.EXPANDED_DECK_IDS]
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    private suspend fun setMode(mode: HomeMode) {
        withContext(mainDispatcher) { _uiState.update { it.copy(mode = mode) } }
    }

    private suspend fun setSyncState(syncState: SyncState) {
        withContext(mainDispatcher) { _uiState.update { it.copy(syncState = syncState) } }
    }
}
