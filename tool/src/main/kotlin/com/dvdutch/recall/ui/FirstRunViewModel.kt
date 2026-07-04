package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.engine.RecallEngine
import com.dvdutch.recall.prefs.RecallPreferences
import com.dvdutch.recall.prefs.TextSanitizer
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
 * Drives the first-run flow: collect sync config, persist it, open the (empty)
 * collection, then run a full download (`fullSync(upload=false)`) to pull the
 * collection down from the server. On success the screen navigates to Home; on
 * failure it shows the reason with a retry. The pure phase transitions live in
 * [FirstRunState]; this only wires the side effects.
 */
class FirstRunViewModel(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val engine = RecallEngine(filesDir, dataStore)

    private val _state = MutableStateFlow(FirstRunState.initial(RecallPreferences.DEFAULT_SYNC_ENDPOINT))
    val state: StateFlow<FirstRunState> = _state.asStateFlow()

    /** Which field editor is open, or null on the intro list. Mirrors Settings' modes. */
    private val _editing = MutableStateFlow<FirstRunField?>(null)
    val editing: StateFlow<FirstRunField?> = _editing.asStateFlow()

    /** Bumped on each editor open so the SDK editor re-seeds its field. */
    private val _editorSession = MutableStateFlow(0)
    val editorSession: StateFlow<Int> = _editorSession.asStateFlow()

    fun openEditor(field: FirstRunField) {
        _editorSession.update { it + 1 }
        _editing.value = field
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun submitField(field: FirstRunField, raw: CharSequence) {
        // The SDK editor inserts a newline for the return key and never trims; sanitize
        // at our boundary so a stray return or edge whitespace can't break sync login.
        _state.update {
            when (field) {
                FirstRunField.Endpoint -> it.copy(endpoint = TextSanitizer.sanitizeEndpoint(raw))
                FirstRunField.Username -> it.copy(username = TextSanitizer.sanitizeCredential(raw))
                FirstRunField.Password -> it.copy(password = TextSanitizer.sanitizeCredential(raw))
            }
        }
        _editing.value = null
    }

    /**
     * Persists the config and runs the full download. No-ops if the fields aren't
     * complete or a download is already in flight.
     */
    fun startDownload() {
        val current = _state.value
        if (!current.canDownload || current.phase is FirstRunPhase.Downloading) return
        _state.update { it.startDownloading() }
        viewModelScope.launch(Dispatchers.IO) {
            val config = current.syncConfig()
            runCatching {
                dataStore.edit { prefs ->
                    prefs[RecallPreferences.SYNC_ENDPOINT] = config.endpoint
                    prefs[RecallPreferences.SYNC_USERNAME] = config.username
                    prefs[RecallPreferences.SYNC_PASSWORD] = config.password
                }
            }
            try {
                engine.openCollection()
                val controller = engine.controller()
                controller.login()               // fail fast on bad credentials
                controller.fullSync(upload = false) // pull the whole collection down
                setState { it.succeeded() }
            } catch (t: Throwable) {
                setState { it.failed(t.message ?: "download failed") }
            }
        }
    }

    private suspend fun setState(transform: (FirstRunState) -> FirstRunState) {
        withContext(Dispatchers.Main) { _state.update(transform) }
    }
}

/** The three editable first-run fields. */
enum class FirstRunField { Endpoint, Username, Password }
