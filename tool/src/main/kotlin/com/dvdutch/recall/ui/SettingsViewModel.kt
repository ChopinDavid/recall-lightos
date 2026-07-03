package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

/** Which sub-screen of Settings is on screen. Mirrors weather's mode enum. */
sealed class SettingsMode {
    /** The settings list: URL + token rows, Test-connection action, status line. */
    data object Main : SettingsMode()

    /** Full-screen editor for the bridge URL. */
    data object EditUrl : SettingsMode()

    /** Full-screen editor for the bridge token. */
    data object EditToken : SettingsMode()
}

data class SettingsUiState(
    val mode: SettingsMode = SettingsMode.Main,
    val bridgeUrl: String = RecallPreferences.DEFAULT_BRIDGE_URL,
    val bridgeToken: String = "",
    val statusLine: String? = null,
    val testing: Boolean = false,
    /** Bumped each time an editor opens so the SDK editor re-seeds its field. */
    val editorSession: Int = 0,
)

/**
 * ViewModel for [SettingsScreen]. DataStore is reached through the SDK exactly
 * as weather does: the owning [com.thelightphone.sdk.LightScreen] passes
 * `lightContext.dataStore` into the constructor, and reads/writes go through
 * `dataStore.data.first()` / `dataStore.edit { }`.
 */
class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadStoredState() }
        }
    }

    private suspend fun loadStoredState() {
        val prefs = dataStore.data.first()
        val url = prefs[RecallPreferences.BRIDGE_URL] ?: RecallPreferences.DEFAULT_BRIDGE_URL
        val token = prefs[RecallPreferences.BRIDGE_TOKEN] ?: ""
        updateState { it.copy(bridgeUrl = url, bridgeToken = token) }
    }

    private suspend fun updateState(transform: (SettingsUiState) -> SettingsUiState) {
        withContext(Dispatchers.Main) { _uiState.update(transform) }
    }

    fun openEditUrl() {
        _uiState.update {
            it.copy(mode = SettingsMode.EditUrl, editorSession = it.editorSession + 1)
        }
    }

    fun openEditToken() {
        _uiState.update {
            it.copy(mode = SettingsMode.EditToken, editorSession = it.editorSession + 1)
        }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(mode = SettingsMode.Main) }
    }

    fun submitUrl(raw: CharSequence) {
        val url = raw.toString().trim()
        _uiState.update { it.copy(bridgeUrl = url, mode = SettingsMode.Main, statusLine = null) }
        persist(RecallPreferences.BRIDGE_URL, url)
    }

    fun submitToken(raw: CharSequence) {
        val token = raw.toString().trim()
        _uiState.update { it.copy(bridgeToken = token, mode = SettingsMode.Main, statusLine = null) }
        persist(RecallPreferences.BRIDGE_TOKEN, token)
    }

    private fun persist(key: Preferences.Key<String>, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dataStore.edit { prefs -> prefs[key] = value } }
        }
    }

    /** Runs a live `/v1/status` call and maps the outcome onto [SettingsMessages]. */
    fun testConnection() {
        val state = _uiState.value
        if (state.testing) return
        val url = state.bridgeUrl
        val token = state.bridgeToken
        _uiState.update { it.copy(testing = true, statusLine = "testing…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = BridgeClient(baseUrl = url, token = token)
            val line = try {
                val status = client.status()
                SettingsMessages.okLine(status)
            } catch (e: BridgeError) {
                SettingsMessages.errorLine(e, url)
            } catch (e: Exception) {
                SettingsMessages.errorLine(BridgeError.Unreachable, url)
            } finally {
                runCatching { client.close() }
            }
            updateState { it.copy(testing = false, statusLine = line) }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
    }
}
