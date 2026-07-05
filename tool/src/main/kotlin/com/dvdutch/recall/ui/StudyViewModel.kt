package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.dvdutch.recall.api.EngineApi
import com.dvdutch.recall.audio.CardAudioPlayer
import com.dvdutch.recall.engine.RecallEngine
import com.dvdutch.recall.study.StudyMachine
import com.dvdutch.recall.study.StudyState
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
    /**
     * The serial confinement lane for every machine call. Defaults to a single-
     * parallelism slice of [Dispatchers.Default] so all machine access is
     * serialized; overridable in tests.
     */
    private val driver: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    // Test seams (default-args keep production call sites unchanged). The engine is
    // injectable; [scope]/[mainDispatcher] make the state-mirror coroutine run without
    // an Android Main dispatcher; and the machine/audio factories let a test drive the
    // real [StudyMachine] over a fake [EngineApi] and observe audio without the Android
    // MediaPlayer. Defaults reproduce the previous inline construction exactly.
    private val engine: RecallEngine = RecallEngine(filesDir, dataStore),
    // Null in production → resolves to [viewModelScope]; tests pass their own scope.
    injectedScope: CoroutineScope? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val machineFactory: (EngineApi) -> StudyMachine = { api ->
        StudyMachine(
            client = api,
            deckId = deckId,
            nowMs = { System.currentTimeMillis() },
            uuid = { UUID.randomUUID().toString() },
        )
    },
    private val audioPlayerFactory: () -> CardAudioPlayer = {
        CardAudioPlayer(resolve = engine.storage::mediaFile)
    },
) : LightViewModel<Unit>() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow<StudyState>(StudyState.Loading)
    val state: StateFlow<StudyState> = _state.asStateFlow()

    /**
     * The session's image loader, reading media straight from the on-device
     * `collection.media` dir. Published in [begin]; shares one ~16-entry LRU across
     * every card. Null until then (render views fall back to placeholders).
     */
    private val _mediaLoader = MutableStateFlow<MediaLoader?>(null)
    val mediaLoader: StateFlow<MediaLoader?> = _mediaLoader.asStateFlow()

    /**
     * The session's audio player, reading `[sound:]` media straight from the on-device
     * `collection.media` dir (same resolution path as [MediaLoader]). Built in [begin]
     * once the engine's storage is available and released on session teardown. Null
     * until then, so early play/stop calls are no-ops. Its own single-thread runner keeps
     * MediaPlayer I/O off both the UI and the machine's [driver] lane.
     */
    private var audioPlayer: CardAudioPlayer? = null

    private var machine: StudyMachine? = null

    /** True once [finish] has run so we never double-finish on hide + back. */
    private var finished = false

    /**
     * Whether the full-screen type-answer text editor is open. The UI opens it from the
     * "TYPE ANSWER" row on a type-answer card's front; it closes on submit/cancel. Local
     * to the ViewModel because it is pure screen state, not part of the study machine.
     */
    private val _typeAnswerEditing = MutableStateFlow(false)
    val typeAnswerEditing: StateFlow<Boolean> = _typeAnswerEditing.asStateFlow()

    /** Bumped on each editor open so the SDK editor re-seeds its (empty) field. */
    private val _typeAnswerSession = MutableStateFlow(0)
    val typeAnswerSession: StateFlow<Int> = _typeAnswerSession.asStateFlow()

    /** Opens the type-answer editor over the current front. */
    fun openTypeAnswerEditor() {
        _typeAnswerSession.value += 1
        _typeAnswerEditing.value = true
    }

    /** Closes the type-answer editor without recording anything. */
    fun cancelTypeAnswer() {
        _typeAnswerEditing.value = false
    }

    /**
     * Records the typed answer on the machine and closes the editor. Sanitized with the
     * shared credential rules (strip newlines/control chars, trim ends) but NOT the
     * endpoint rule — a typed answer legitimately contains interior spaces (e.g. "New York").
     */
    fun submitTypeAnswer(raw: CharSequence) {
        val m = machine
        val clean = com.dvdutch.recall.prefs.TextSanitizer.sanitizeCredential(raw)
        _typeAnswerEditing.value = false
        if (m != null) scope.launch(driver) { m.setTypedAnswer(clean) }
    }

    /** Starts (or restarts, on retry) the session. Idempotent per screen show. */
    fun begin() {
        if (machine != null) return
        scope.launch(driver) {
            engine.openCollection()
            val controller = engine.controller().takeIf { it.configured }
            val api = engine.api(controller)
            val m = machineFactory(api)
            machine = m
            _mediaLoader.value = MediaLoader(engine.storage)
            audioPlayer = audioPlayerFactory()
            // Mirror the machine's state into our surfaced flow.
            scope.launch(mainDispatcher) {
                m.state.collect { _state.value = it }
            }
            m.start()
        }
    }

    fun reveal() {
        val m = machine ?: return
        scope.launch(driver) { m.reveal() }
    }

    fun grade(rating: String) {
        val m = machine ?: return
        // Stop this card's audio before advancing so it never bleeds into the next
        // card. The next card's auto-play would supersede it anyway, but grading may
        // reach Finished (no next card), and a lingering track then would be wrong.
        audioPlayer?.stop()
        scope.launch(driver) { m.grade(rating) }
    }

    /**
     * Undoes the last grade via the machine (rslib's own undo). Routed through the
     * same serial [driver] lane as grade/reveal so the undo can never interleave
     * with an in-flight grade mutating the buffer/state. Stops any playing audio
     * first, exactly as [grade] does, so the reverted card starts clean.
     */
    fun undo() {
        val m = machine ?: return
        audioPlayer?.stop()
        scope.launch(driver) { m.undo() }
    }

    /**
     * Buries the current card (backend op). Stops audio and advances on the serial lane,
     * exactly like [grade] — the buried card leaves the queue and the next card appears.
     */
    fun buryCard() {
        val m = machine ?: return
        audioPlayer?.stop()
        scope.launch(driver) { m.buryCurrent() }
    }

    /**
     * Suspends the current card (backend op). Same lane/teardown discipline as [buryCard];
     * the card leaves the queue until unsuspended on desktop.
     */
    fun suspendCard() {
        val m = machine ?: return
        audioPlayer?.stop()
        scope.launch(driver) { m.suspendCurrent() }
    }

    /**
     * Toggles the "marked" tag on the current note (backend op). Does NOT advance or touch
     * audio: only the mark indicator flips. Routed through the serial lane so it orders
     * against grades/reveals.
     */
    fun toggleMark() {
        val m = machine ?: return
        scope.launch(driver) { m.toggleMarkCurrent() }
    }

    /** Plays [filenames] for the current side; a no-op empty list clears playback. */
    fun playAudio(filenames: List<String>) {
        audioPlayer?.play(filenames)
    }

    /** Retry the whole session after a retriable failure by re-starting it. */
    fun retry() {
        val m = machine ?: return begin()
        scope.launch(driver) { m.start() }
    }

    /** Best-effort finish; safe to call from both hide and back. */
    fun finishSession() {
        if (finished) return
        finished = true
        // Release the audio player on the SAME teardown path as the machine finish so
        // leaving the screen (hide / pause / back) never leaves a track playing or a
        // MediaPlayer un-released.
        audioPlayer?.release()
        val m = machine
        scope.launch(driver) {
            runCatching { m?.finish() }
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

    /** Final safety net: release the player if the ViewModel is cleared without a hide. */
    override fun onCleared() {
        super.onCleared()
        audioPlayer?.release()
    }
}
