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
 * the single coroutine context that drives it.
 *
 * [StudyMachine] is NOT thread-safe (plain mutable buffer/counters and a
 * [kotlinx.coroutines.flow.MutableStateFlow] it mutates without locking), so
 * every `begin`/`reveal`/`grade`/`retry`/`finish` is `launch`ed on [driver] — a
 * `Dispatchers.Default.limitedParallelism(1)` dispatcher, i.e. a serial single-
 * lane executor. `limitedParallelism(1)` guarantees at most one coroutine runs on
 * it at a time, so these `launch`es form one queue and the machine is never
 * touched concurrently. This closes the races a plain multi-threaded dispatcher
 * (e.g. [Dispatchers.IO]) allowed: a double-tap grade can no longer have two
 * `grade()` bodies pass the ShowingBack guard at once, and a finish triggered by
 * hide/pause/back can no longer interleave with an in-flight grade mutating the
 * buffer or state. `reveal()` is synchronous but is routed through the same lane
 * so its state write is ordered against grades too. The machine's own
 * [StudyState] flow is surfaced directly to the UI.
 *
 * The bridge client is built from the persisted URL + token exactly as the other
 * ViewModels do. The machine is created lazily on the first [begin] once those
 * prefs are read.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudyViewModel(
    private val deckId: Long,
    private val dataStore: DataStore<Preferences>,
    /**
     * The serial confinement lane for every machine call. Defaults to a single-
     * parallelism slice of [Dispatchers.Default] so all machine access is
     * serialized; overridable in tests.
     */
    private val driver: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<StudyState>(StudyState.Loading)
    val state: StateFlow<StudyState> = _state.asStateFlow()

    /**
     * The session's image loader, published once the bridge client is built in
     * [begin]. Null until then (and the render views fall back to placeholders).
     * Shares the session client and its ~16-entry LRU across every card.
     */
    private val _mediaLoader = MutableStateFlow<MediaLoader?>(null)
    val mediaLoader: StateFlow<MediaLoader?> = _mediaLoader.asStateFlow()

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
            _mediaLoader.value = MediaLoader(c)
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
