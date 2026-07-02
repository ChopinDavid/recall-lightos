package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.api.BridgeClient
import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.prefs.RecallPreferences
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the deck-list screen is currently showing. */
sealed interface HomeMode {
    /** Prefs not yet read, or a decks fetch is in flight. */
    data object Loading : HomeMode

    /** No token configured — the screen auto-navigates to Settings. */
    data object Unconfigured : HomeMode

    /** Decks loaded; [rows] is the flattened, indented tree. */
    data class Loaded(val rows: List<DeckRow>) : HomeMode

    /** A decks fetch failed; [message] is the mapped [SettingsMessages] copy. */
    data class Error(val message: String) : HomeMode
}

data class HomeUiState(val mode: HomeMode = HomeMode.Loading)

/**
 * ViewModel for [RecallHomeScreen]. On show it reads the persisted bridge URL +
 * token from DataStore (same access path as [SettingsViewModel]); with no token
 * it reports [HomeMode.Unconfigured] so the screen bounces to Settings, otherwise
 * it fetches `/v1/decks` and flattens them into [DeckRow]s. Every bridge failure
 * maps onto [SettingsMessages] copy with a retry affordance.
 */
class RecallHomeViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Re-load whenever the screen becomes visible (e.g. returning from Settings). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        load()
    }

    fun load() {
        _uiState.update { it.copy(mode = HomeMode.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = runCatching { dataStore.data.first() }.getOrNull()
            val url = prefs?.get(RecallPreferences.BRIDGE_URL) ?: RecallPreferences.DEFAULT_BRIDGE_URL
            val token = prefs?.get(RecallPreferences.BRIDGE_TOKEN).orEmpty()

            if (token.isBlank()) {
                setMode(HomeMode.Unconfigured)
                return@launch
            }

            val client = BridgeClient(baseUrl = url, token = token)
            val mode = try {
                val rows = deckRows(client.decks())
                HomeMode.Loaded(rows)
            } catch (e: BridgeError) {
                HomeMode.Error(SettingsMessages.errorLine(e, url))
            } catch (_: Exception) {
                HomeMode.Error(SettingsMessages.errorLine(BridgeError.Unreachable, url))
            } finally {
                runCatching { client.close() }
            }
            setMode(mode)
        }
    }

    private suspend fun setMode(mode: HomeMode) {
        withContext(Dispatchers.Main) { _uiState.update { it.copy(mode = mode) } }
    }
}
