package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.engine.RecallEngine
import com.dvdutch.recall.prefs.RecallPreferences
import com.dvdutch.recall.prefs.TextSanitizer
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
import java.io.File

/** Which sub-screen of Settings is on screen. Mirrors weather's mode enum. */
sealed class SettingsMode {
    /** The settings list: endpoint/username/password rows, Test-login action, status. */
    data object Main : SettingsMode()

    /** Full-screen editor for the sync endpoint. */
    data object EditEndpoint : SettingsMode()

    /** Full-screen editor for the sync username. */
    data object EditUsername : SettingsMode()

    /** Full-screen editor for the sync password. */
    data object EditPassword : SettingsMode()
}

data class SettingsUiState(
    val mode: SettingsMode = SettingsMode.Main,
    val endpoint: String = RecallPreferences.DEFAULT_SYNC_ENDPOINT,
    val username: String = "",
    val password: String = "",
    /** The last-login/test status line under the fields (null = nothing yet). */
    val statusLine: String? = null,
    /** The last clean sync time, shown as a relative label; null = never synced. */
    val lastSync: Long? = null,
    val testing: Boolean = false,
    /** Bumped each time an editor opens so the SDK editor re-seeds its field. */
    val editorSession: Int = 0,
)

/**
 * ViewModel for [SettingsScreen]. Sync-era: three fields (endpoint / username /
 * password) persisted to DataStore exactly as weather does, plus a "Test login"
 * action that runs a real `syncLogin` through [RecallEngine]/[SyncController] and
 * reports the outcome via [SettingsMessages].
 */
class SettingsViewModel(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val engine = RecallEngine(filesDir, dataStore)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadStoredState() }
        }
    }

    private suspend fun loadStoredState() {
        val prefs = dataStore.data.first()
        val endpoint = prefs[RecallPreferences.SYNC_ENDPOINT] ?: RecallPreferences.DEFAULT_SYNC_ENDPOINT
        val username = prefs[RecallPreferences.SYNC_USERNAME].orEmpty()
        val password = prefs[RecallPreferences.SYNC_PASSWORD].orEmpty()
        updateState { it.copy(endpoint = endpoint, username = username, password = password) }
    }

    private suspend fun updateState(transform: (SettingsUiState) -> SettingsUiState) {
        withContext(Dispatchers.Main) { _uiState.update(transform) }
    }

    fun openEditEndpoint() = openEditor(SettingsMode.EditEndpoint)
    fun openEditUsername() = openEditor(SettingsMode.EditUsername)
    fun openEditPassword() = openEditor(SettingsMode.EditPassword)

    private fun openEditor(mode: SettingsMode) {
        _uiState.update { it.copy(mode = mode, editorSession = it.editorSession + 1) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(mode = SettingsMode.Main) }
    }

    // The SDK editor inserts a newline for the return key and never trims; sanitize
    // at our boundary so a stray return or edge whitespace can't break sync login.
    fun submitEndpoint(raw: CharSequence) =
        submitField(RecallPreferences.SYNC_ENDPOINT, TextSanitizer.sanitizeEndpoint(raw)) { s, v -> s.copy(endpoint = v) }

    fun submitUsername(raw: CharSequence) =
        submitField(RecallPreferences.SYNC_USERNAME, TextSanitizer.sanitizeCredential(raw)) { s, v -> s.copy(username = v) }

    fun submitPassword(raw: CharSequence) =
        submitField(RecallPreferences.SYNC_PASSWORD, TextSanitizer.sanitizeCredential(raw)) { s, v -> s.copy(password = v) }

    private fun submitField(
        key: Preferences.Key<String>,
        value: String,
        apply: (SettingsUiState, String) -> SettingsUiState,
    ) {
        _uiState.update { apply(it, value).copy(mode = SettingsMode.Main, statusLine = null) }
        persist(key, value)
    }

    private fun persist(key: Preferences.Key<String>, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dataStore.edit { prefs -> prefs[key] = value } }
        }
    }

    /** Runs a live `syncLogin` and maps the outcome onto [SettingsMessages]. */
    fun testLogin() {
        val state = _uiState.value
        if (state.testing) return
        _uiState.update { it.copy(testing = true, statusLine = "testing…") }
        viewModelScope.launch(Dispatchers.IO) {
            val controller = engine.controller()
            val line = if (!controller.configured) {
                "fill in endpoint, username and password first"
            } else {
                try {
                    controller.login()
                    SettingsMessages.loginOkLine()
                } catch (t: Throwable) {
                    SettingsMessages.loginFailedLine(t.message)
                }
            }
            updateState { it.copy(testing = false, statusLine = line) }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
    }
}
