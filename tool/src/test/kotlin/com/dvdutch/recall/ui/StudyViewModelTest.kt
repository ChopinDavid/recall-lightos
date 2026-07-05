package com.dvdutch.recall.ui

import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.AnswerResult
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import com.dvdutch.recall.api.EngineApi
import com.dvdutch.recall.api.QueueResponse
import com.dvdutch.recall.api.StudyStartResponse
import com.dvdutch.recall.api.SyncInfo
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.UndoResult
import com.dvdutch.recall.audio.CardAudioPlayer
import com.dvdutch.recall.audio.MediaPlayback
import com.dvdutch.recall.study.StudyMachine
import com.dvdutch.recall.study.StudyState
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The study session ViewModel ([StudyViewModel]) as a DISPATCHER: it owns one
 * [StudyMachine] and routes begin/reveal/grade/undo/bury/suspend/mark and typed-answer
 * input onto the serial driver lane, and drives audio play/stop around grading. These
 * tests inject a real machine over a scriptable [EngineApi] (so machine calls are asserted
 * by their engine effects) and an observable audio player (so play/stop are asserted
 * without the Android MediaPlayer). All lanes run on [Dispatchers.Unconfined] so each
 * dispatched body completes inline and the surfaced state settles synchronously.
 */
class StudyViewModelTest {

    /** A scriptable EngineApi: canned outcomes per collaborator, recorded call args. */
    private class FakeApi : EngineApi {
        val startScript = ArrayDeque<StudyStartResponse>()
        val queueScript = ArrayDeque<QueueResponse>()
        val answerScript = ArrayDeque<List<AnswerResult>>()
        val undoScript = ArrayDeque<UndoResult>()

        val answerArgs = mutableListOf<List<AnswerIn>>()
        val buryArgs = mutableListOf<Long>()
        val suspendArgs = mutableListOf<Long>()
        val markArgs = mutableListOf<Long>()
        var finishCalls = 0
        val compareArgs = mutableListOf<Triple<String, String, Boolean>>()
        var compareResult = "<code id=typeans><span class=typeGood>Paris</span></code>"

        override suspend fun studyStart(deckId: Long): StudyStartResponse = startScript.removeFirst()
        override suspend fun queue(limit: Int): QueueResponse =
            if (queueScript.isNotEmpty()) queueScript.removeFirst() else QueueResponse(emptyList(), Counts(0, 0, 0))
        override suspend fun answer(answers: List<AnswerIn>): List<AnswerResult> {
            answerArgs.add(answers); return answerScript.removeFirst()
        }
        override suspend fun studyFinish(): SyncInfo { finishCalls++; return SyncInfo(synced = true, detail = "ok") }
        override suspend fun undo(): UndoResult = undoScript.removeFirst()
        override suspend fun buryCard(cardId: Long) { buryArgs.add(cardId) }
        override suspend fun suspendCard(cardId: Long) { suspendArgs.add(cardId) }
        override suspend fun toggleMark(noteId: Long): Boolean { markArgs.add(noteId); return true }
        override suspend fun compareTypedAnswer(expected: String, provided: String, noCase: Boolean): String {
            compareArgs.add(Triple(expected, provided, noCase)); return compareResult
        }
    }

    private fun card(id: Long, states: String = "s$id", type: String? = null): CardPayload = CardPayload(
        cardId = id,
        noteId = id * 10,
        front = listOf(TextNode(listOf(TextRun("front $id")))),
        back = listOf(TextNode(listOf(TextRun("back $id")))),
        states = states,
        nextDueLabels = mapOf("good" to "1d"),
        marked = false,
        typeAnswerExpected = type,
    )

    private fun counts(new: Int = 0) = Counts(new = new, learning = 0, review = 0)

    // A recording MediaPlayback factory: every play() builds a player, so counting
    // setDataSource/stop across the shared factory reveals the queue behaviour.
    private class AudioRecorder {
        val starts = mutableListOf<String>()
        var stops = 0
        var releases = 0
        val factory: () -> MediaPlayback = {
            object : MediaPlayback {
                var src: String = ""
                override fun setDataSource(path: String) { src = path }
                override fun prepare() {}
                override fun start() { starts.add(src) }
                override fun setOnCompletion(cb: () -> Unit) {}
                override fun stop() { stops++ }
                override fun release() { releases++ }
            }
        }
    }

    private class Env(val dir: File) {
        val api = FakeApi()
        val recorder = AudioRecorder()
        // Resolve every requested media name to a real temp file so playback actually starts.
        val mediaDir = File(dir, "media").apply { mkdirs() }
        val audio = CardAudioPlayer(
            resolve = { name -> File(mediaDir, name).apply { if (!exists()) writeBytes(byteArrayOf(1)) } },
            factory = recorder.factory,
            runner = { it.run() },
        )
    }

    private fun build(
        env: Env,
        ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    ): StudyViewModel = StudyViewModel(
        deckId = 42L,
        filesDir = env.dir,
        dataStore = ds,
        driver = Dispatchers.Unconfined,
        engine = FakeEngine(env.dir, ds, collectionPresent = false),
        injectedScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        mainDispatcher = Dispatchers.Unconfined,
        machineFactory = { _ ->
            StudyMachine(client = env.api, deckId = 42L, nowMs = { 1_000L }, uuid = { "u" })
        },
        audioPlayerFactory = { env.audio },
    )

    private fun withVm(block: (StudyViewModel, Env) -> Unit) = withEnv("study-vm") { dir, _, ds ->
        val env = Env(dir)
        block(build(env, ds), env)
    }

    // begin() starts the machine: studyStart + queue drive the surfaced state to the first card.
    @Test
    fun `begin starts the machine and surfaces the first card`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))

            vm.begin()

            waitFor(message = { "ShowingFront" }) { vm.state.value is StudyState.ShowingFront }
            val s = vm.state.value as StudyState.ShowingFront
            assertEquals(1L, s.card.cardId)
        }
    }

    // begin() is idempotent: a second begin does not re-start the machine.
    @Test
    fun `begin is idempotent`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.begin() // no more scripted responses; a re-start would throw on removeFirst()

            assertTrue(vm.state.value is StudyState.ShowingFront)
        }
    }

    // reveal → grade("good") dispatches to the machine: an AnswerIn for the current card is
    // posted with the echoed states, and the session advances.
    @Test
    fun `grade posts an answer for the current card and advances`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1, "STATE-1"), card(2, "STATE-2")), counts(new = 2)))
            env.api.queueScript.add(QueueResponse(emptyList(), counts(new = 1)))
            env.api.answerScript.add(listOf(AnswerResult("u", "applied")))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.reveal()
            vm.grade("good")

            waitFor(message = { "advanced" }) {
                (vm.state.value as? StudyState.ShowingFront)?.card?.cardId == 2L
            }
            val posted = env.api.answerArgs.single().single()
            assertEquals(1L, posted.cardId)
            assertEquals("good", posted.rating)
            assertEquals("STATE-1", posted.states)
        }
    }

    // bury dispatches buryCard(currentCardId) to the machine and advances.
    @Test
    fun `bury dispatches buryCard for the current card`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))
            env.api.queueScript.add(QueueResponse(listOf(card(2)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.buryCard()

            waitFor(message = { "buried" }) { env.api.buryArgs == listOf(1L) }
            assertEquals(listOf(1L), env.api.buryArgs)
        }
    }

    // suspend dispatches suspendCard(currentCardId) to the machine.
    @Test
    fun `suspend dispatches suspendCard for the current card`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))
            env.api.queueScript.add(QueueResponse(listOf(card(2)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.suspendCard()

            waitFor(message = { "suspended" }) { env.api.suspendArgs == listOf(1L) }
            assertEquals(listOf(1L), env.api.suspendArgs)
        }
    }

    // mark dispatches toggleMark(currentNoteId) without advancing the card.
    @Test
    fun `mark dispatches toggleMark for the current note without advancing`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.toggleMark()

            waitFor(message = { "marked" }) { env.api.markArgs == listOf(10L) }
            assertEquals(listOf(10L), env.api.markArgs) // note id = card 1 * 10
            assertEquals(1L, (vm.state.value as StudyState.ShowingFront).card.cardId) // did not advance
        }
    }

    // undo dispatches the engine's own undo and brings the card back.
    @Test
    fun `undo dispatches the engine undo`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))
            env.api.queueScript.add(QueueResponse(emptyList(), counts(new = 1)))
            env.api.answerScript.add(listOf(AnswerResult("u", "applied")))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }
            vm.reveal(); vm.grade("good")
            waitFor { (vm.state.value as? StudyState.ShowingFront)?.card?.cardId == 2L }

            env.api.undoScript.add(UndoResult(undone = true, undoableAnswer = false))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))
            vm.undo()

            waitFor(message = { "undone to card 1" }) {
                (vm.state.value as? StudyState.ShowingFront)?.card?.cardId == 1L
            }
        }
    }

    // The typed-answer lifecycle: submit records it on the machine (open→submit); on reveal
    // the machine calls compareTypedAnswer with the typed value; advancing clears it so the
    // next card's reveal shows the plain expected answer (no stale diff).
    @Test
    fun `typed answer is recorded, used on reveal, and cleared on advance`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(
                QueueResponse(listOf(card(1, type = "Paris"), card(2, type = "Berlin")), counts(new = 2)),
            )
            env.api.queueScript.add(QueueResponse(emptyList(), counts(new = 1)))
            env.api.answerScript.add(listOf(AnswerResult("u", "applied")))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.openTypeAnswerEditor()
            assertTrue(vm.typeAnswerEditing.value)
            val session = vm.typeAnswerSession.value

            vm.submitTypeAnswer("paris")
            assertTrue(!vm.typeAnswerEditing.value, "submit closes the editor")

            vm.reveal()
            waitFor(message = { "compare called" }) { env.api.compareArgs.isNotEmpty() }
            assertEquals(Triple("Paris", "paris", false), env.api.compareArgs.single())

            vm.grade("good")
            waitFor { (vm.state.value as? StudyState.ShowingFront)?.card?.cardId == 2L }

            // Card 2 revealed WITHOUT typing → no second compare call (input was cleared).
            vm.reveal()
            waitFor { vm.state.value is StudyState.ShowingBack }
            assertEquals(1, env.api.compareArgs.size, "the typed answer must not leak to card 2")
            assertTrue(vm.typeAnswerSession.value >= session)
        }
    }

    // playAudio dispatches to the player (a resolvable file actually starts).
    @Test
    fun `playAudio dispatches playback of the requested files`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.playAudio(listOf("hello.mp3"))

            waitFor(message = { "audio started" }) { env.recorder.starts.any { it.endsWith("hello.mp3") } }
            assertTrue(env.recorder.starts.any { it.endsWith("hello.mp3") })
        }
    }

    // finishSession finishes the machine (studyFinish) and releases audio; it is idempotent
    // so a hide + back can't double-finish.
    @Test
    fun `finishSession finishes the machine once and releases audio`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }
            vm.playAudio(listOf("a.mp3"))
            waitFor { env.recorder.starts.isNotEmpty() }

            vm.finishSession()
            waitFor(message = { "studyFinish called" }) { env.api.finishCalls == 1 }

            vm.finishSession() // second call: idempotent, no second finish
            assertEquals(1, env.api.finishCalls, "finish is guarded against double-finish")
            assertTrue(env.recorder.releases >= 1, "audio released on teardown")
        }
    }

    // onAppPause routes to the same teardown path (studyFinish).
    @Test
    fun `onAppPause finishes the session`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1)), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }

            vm.onAppPause()

            waitFor(message = { "finished on pause" }) { env.api.finishCalls == 1 }
        }
    }

    // cancelTypeAnswer closes the editor without recording anything.
    @Test
    fun `cancelTypeAnswer closes the editor`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 1), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1, type = "Paris")), counts(new = 1)))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }
            vm.openTypeAnswerEditor()
            assertTrue(vm.typeAnswerEditing.value)

            vm.cancelTypeAnswer()

            assertTrue(!vm.typeAnswerEditing.value)
        }
    }

    // grade() stops the current card's audio before advancing (so it never bleeds over).
    @Test
    fun `grade stops audio before advancing`() {
        withVm { vm, env ->
            env.api.startScript.add(StudyStartResponse(counts(new = 2), SyncInfo(true, "ok")))
            env.api.queueScript.add(QueueResponse(listOf(card(1), card(2)), counts(new = 2)))
            env.api.queueScript.add(QueueResponse(emptyList(), counts(new = 1)))
            env.api.answerScript.add(listOf(AnswerResult("u", "applied")))
            vm.begin()
            waitFor { vm.state.value is StudyState.ShowingFront }
            vm.playAudio(listOf("card1.mp3"))
            waitFor { env.recorder.starts.isNotEmpty() }
            val stopsBefore = env.recorder.stops

            vm.reveal(); vm.grade("good")

            waitFor(message = { "audio stopped on grade" }) { env.recorder.stops > stopsBefore }
            assertTrue(env.recorder.stops > stopsBefore)
        }
    }
}
