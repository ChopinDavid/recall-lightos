package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.api.BridgeClient
import com.dvdutch.recall.prefs.RecallPreferences
import com.dvdutch.recall.study.StudyMachine
import com.dvdutch.recall.study.StudyState
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for [StudyScreen]. It owns one [StudyMachine] for the session and is
 * the single coroutine context that drives it: every `start`/`reveal`/`grade`/
 * `finish` is `launch`ed on [driver] (a single-threaded confinement), so the
 * not-thread-safe machine is never touched concurrently. The machine's own
 * [StudyState] flow is surfaced directly to the UI.
 *
 * The bridge client is built from the persisted URL + token exactly as the other
 * ViewModels do. The machine is created lazily on the first [begin] once those
 * prefs are read.
 */
class StudyViewModel(
    private val deckId: Long,
    private val dataStore: DataStore<Preferences>,
    /** Confines every machine call to one thread; overridable in tests. */
    private val driver: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<StudyState>(StudyState.Loading)
    val state: StateFlow<StudyState> = _state.asStateFlow()

    private var client: BridgeClient? = null
    private var machine: StudyMachine? = null

    /** True once [finish] has run so we never double-finish on hide + back. */
    private var finished = false

    /** Starts (or restarts, on retry) the session. Idempotent per screen show. */
    fun begin() {
        if (machine != null) return
        viewModelScope.launch(driver) {
            val prefs = dataStore.data.first()
            val url = prefs[RecallPreferences.BRIDGE_URL] ?: RecallPreferences.DEFAULT_BRIDGE_URL
            val token = prefs[RecallPreferences.BRIDGE_TOKEN].orEmpty()
            val c = BridgeClient(baseUrl = url, token = token)
            val m = StudyMachine(
                client = c,
                deckId = deckId,
                nowMs = { System.currentTimeMillis() },
                uuid = { UUID.randomUUID().toString() },
            )
            client = c
            machine = m
            // Mirror the machine's state into our surfaced flow.
            viewModelScope.launch(Dispatchers.Main) {
                m.state.collect { _state.value = it }
            }
            m.start()
        }
    }

    fun reveal() {
        val m = machine ?: return
        viewModelScope.launch(driver) { m.reveal() }
    }

    fun grade(rating: String) {
        val m = machine ?: return
        viewModelScope.launch(driver) { m.grade(rating) }
    }

    /** Retry the whole session after a retriable failure by re-starting it. */
    fun retry() {
        val m = machine ?: return begin()
        viewModelScope.launch(driver) { m.start() }
    }

    /** Best-effort finish; safe to call from both hide and back. */
    fun finishSession() {
        if (finished) return
        finished = true
        val m = machine
        viewModelScope.launch(driver) {
            runCatching { m?.finish() }
            runCatching { client?.close() }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        finishSession()
    }

    override fun onAppPause() {
        super.onAppPause()
        finishSession()
    }
}
