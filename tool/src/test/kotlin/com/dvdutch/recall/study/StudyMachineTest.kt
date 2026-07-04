package com.dvdutch.recall.study

import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.AnswerResult
import com.dvdutch.recall.api.EngineApi
import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import com.dvdutch.recall.api.QueueResponse
import com.dvdutch.recall.api.StudyStartResponse
import com.dvdutch.recall.api.SyncInfo
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.UndoResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A scriptable [EngineApi] fake. Each collaborator is backed by a queue of
 * canned outcomes (a value to return or a [BridgeError] to throw) consumed in
 * order; call arguments are recorded for assertions.
 */
private class FakeBridge : EngineApi {
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Fail(val error: BridgeError) : Outcome<Nothing>
    }

    val startScript = ArrayDeque<Outcome<StudyStartResponse>>()
    val queueScript = ArrayDeque<Outcome<QueueResponse>>()
    val answerScript = ArrayDeque<Outcome<List<AnswerResult>>>()
    val finishScript = ArrayDeque<Outcome<SyncInfo>>()
    val undoScript = ArrayDeque<Outcome<UndoResult>>()
    val buryScript = ArrayDeque<Outcome<Unit>>()
    val suspendScript = ArrayDeque<Outcome<Unit>>()
    val markScript = ArrayDeque<Outcome<Boolean>>()

    var startCalls = 0
    var queueCalls = 0
    var finishCalls = 0
    var undoCalls = 0
    val answerArgs = mutableListOf<List<AnswerIn>>()
    val buryArgs = mutableListOf<Long>()
    val suspendArgs = mutableListOf<Long>()
    val markArgs = mutableListOf<Long>()

    private fun <T> ArrayDeque<Outcome<T>>.next(): T = when (val o = removeFirst()) {
        is Outcome.Ok -> o.value
        is Outcome.Fail -> throw o.error
    }

    override suspend fun studyStart(deckId: Long): StudyStartResponse {
        startCalls++
        return startScript.next()
    }

    override suspend fun queue(limit: Int): QueueResponse {
        queueCalls++
        return queueScript.next()
    }

    override suspend fun answer(answers: List<AnswerIn>): List<AnswerResult> {
        answerArgs.add(answers)
        return answerScript.next()
    }

    override suspend fun studyFinish(): SyncInfo {
        finishCalls++
        return finishScript.next()
    }

    override suspend fun undo(): UndoResult {
        undoCalls++
        return undoScript.next()
    }

    override suspend fun buryCard(cardId: Long) {
        buryArgs.add(cardId)
        buryScript.next()
    }

    override suspend fun suspendCard(cardId: Long) {
        suspendArgs.add(cardId)
        suspendScript.next()
    }

    override suspend fun toggleMark(noteId: Long): Boolean {
        markArgs.add(noteId)
        return markScript.next()
    }
}

class StudyMachineTest {

    private fun card(id: Long, states: String, marked: Boolean = false): CardPayload = CardPayload(
        cardId = id,
        noteId = id * 10,
        front = listOf(TextNode(listOf(TextRun("front $id")))),
        back = listOf(TextNode(listOf(TextRun("back $id")))),
        states = states,
        nextDueLabels = mapOf("good" to "1d"),
        marked = marked,
    )

    private val sync = SyncInfo(synced = true, detail = "ok")

    private fun counts(new: Int = 0, learning: Int = 0, review: Int = 0) =
        Counts(new = new, learning = learning, review = review)

    // A fixed clock: reveal reads 1_000, grade reads 1_200 -> ms_taken == 200.
    private fun clock(vararg values: Long): () -> Long {
        val q = ArrayDeque(values.toList())
        return { if (q.size > 1) q.removeFirst() else q.first() }
    }

    private fun uuids(vararg values: String): () -> String {
        val q = ArrayDeque(values.toList())
        return { q.removeFirst() }
    }

    private fun machine(
        bridge: FakeBridge,
        now: () -> Long = { 0L },
        uuid: () -> String = { "uuid" },
        deckId: Long = 42L,
    ) = StudyMachine(client = bridge, deckId = deckId, nowMs = now, uuid = uuid)

    // start -> ShowingFront with first card, exposing start counts.
    @Test
    fun startShowsFirstCard() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        val m = machine(bridge)

        m.start()

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(1L, state.card.cardId)
        assertEquals(2, state.counts.new)
        assertEquals(1, bridge.startCalls)
        assertEquals(1, bridge.queueCalls)
    }

    // reveal moves to ShowingBack recording shownAt from the clock.
    @Test
    fun revealRecordsShownAt() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        val m = machine(bridge, now = clock(1_000L))
        m.start()

        m.reveal()

        val state = m.state.value
        assertTrue(state is StudyState.ShowingBack, "was $state")
        assertEquals(1_000L, state.shownAtMs)
    }

    // reveal -> grade("good") posts a byte-identical AnswerIn and advances.
    @Test
    fun gradePostsAnswerAndAdvances() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "STATES-1"), card(2, "STATES-2")), counts(new = 2)),
            ),
        )
        // buffer drops to 1 (<5) after answering card 1 -> prefetch returns nothing new.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val posted = bridge.answerArgs.single().single()
        assertEquals("u1", posted.uuid)
        assertEquals(1L, posted.cardId)
        assertEquals("good", posted.rating)
        assertEquals("STATES-1", posted.states) // echoed byte-identical
        assertEquals(200L, posted.msTaken) // 1200 - 1000
        assertEquals(1_200L, posted.answeredAt)

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(2L, state.card.cardId) // advanced to next card
    }

    // queue exhaustion (buffer empty + empty counts) -> Finished with reviewed count.
    @Test
    fun exhaustionFinishesWithReviewedCount() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        // buffer < 5 after removing card 1 -> prefetch returns empty with empty counts.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts())),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.Finished, "was $state")
        assertEquals(1, state.reviewed) // one applied answer counted
    }

    // SERVE-UNTIL-DONE: grading through the ENTIRE initial batch when the engine
    // still has more due cards must KEEP SERVING (never Finished at the batch
    // boundary). The old model capped the session at the first batch; here the
    // prefetch keeps topping up from the engine until it is truly exhausted, so
    // after answering all 20 initial cards we are still ShowingFront on a fresh card.
    @Test
    fun servesPastInitialBatchWhenEngineHasMoreDue() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 29), sync)))
        // The initial batch is the full queue() default (20 cards) with 29 due total.
        val batchA = (1L..20L).map { card(it, "a$it") }
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(batchA, counts(new = 29))))
        // Every grade posts an applied answer.
        repeat(20) { i ->
            bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u$i", "applied"))))
        }
        // advance() drops the head then, while the buffer holds >= 5 cards, does a
        // counts-only queue(1) whose card is DISCARDED (bait). Grades 1..15 keep the
        // buffer at >= 5 (20 - 15 = 5), so they each consume one bait refresh.
        val batchB = (21L..29L).map { card(it, "b$it") }
        repeat(15) {
            bridge.queueScript.add(
                FakeBridge.Outcome.Ok(QueueResponse(listOf(card(99, "bait")), counts(new = 14))),
            )
        }
        // Grade 16 drops the buffer to 4 (< 5) -> full prefetch: the engine STILL has
        // due cards and hands back the remaining nine (ids 21..29), proving serve-until-done.
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(batchB, counts(new = 9))))
        // Grades 17..20 keep the buffer >= 5 again (4 + 9 = 13, down to ~9), so they
        // each consume a bait counts-only refresh.
        repeat(10) {
            bridge.queueScript.add(
                FakeBridge.Outcome.Ok(QueueResponse(listOf(card(99, "bait")), counts(new = 9))),
            )
        }
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids(*Array(20) { "u$it" }))
        m.start()

        // Grade all 20 initial cards. If the old batch cap were in force, the session
        // would go Finished after the 20th; serve-until-done keeps a card in hand.
        repeat(20) {
            m.reveal()
            m.grade("good")
        }

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "expected still serving, was $state")
        // The card in hand comes from the engine's later batch, not the initial one.
        assertTrue(state.card.cardId in 21L..29L, "was card ${state.card.cardId}")
    }

    // Finished carries the counts from the MOST RECENT queue() response even when
    // the buffer is now empty: learning cards may be due later today.
    @Test
    fun finishedCarriesLatestQueueCounts() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        // prefetch returns no cards NOW but reports 3 learning cards due later today.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(learning = 3))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.Finished, "was $state")
        assertEquals(1, state.reviewed)
        assertEquals(3, state.counts?.learning) // "3 more due later today"
    }

    // stale/gone results advance WITHOUT counting toward reviewed.
    @Test
    fun staleResultAdvancesButDoesNotCount() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts())))
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "stale"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.Finished, "was $state")
        assertEquals(0, state.reviewed) // stale did not count
    }

    // grade receiving status "error" -> Failed(retriable=true), same card retained.
    @Test
    fun errorResultFailsRetriableKeepingCard() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(7, "s7")), counts(new = 1))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "error"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1", "u2"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.Failed, "was $state")
        assertTrue(state.retriable)
        assertTrue(state.cause is FailCause.AnswerRejected, "was ${state.cause}")

        // Re-grade is possible: same card is retained. Script another answer.
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u2", "applied"))))
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts())))
        m.grade("good")
        // the second answer was for the same card 7
        assertEquals(7L, bridge.answerArgs.last().single().cardId)
    }

    // A mid-session transport failure then start() again with a fresh queue must
    // NOT stack the stale leftover card: next ShowingFront is queue B's first
    // card. reviewed still counts the one applied answer (real reviews persist).
    @Test
    fun startAfterFailureClearsStaleBuffer() = runBlocking {
        val bridge = FakeBridge()
        // Queue A: two cards. Grade card 1 (applied). Prefetch (buffer 1 < 5) fails.
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "A1"), card(2, "A2")), counts(new = 2)),
            ),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        // Prefetch after advancing to card 2 fails at transport level -> Failed.
        bridge.queueScript.add(FakeBridge.Outcome.Fail(BridgeError.Unreachable))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()
        m.grade("good") // applied -> reviewed=1, then prefetch fails -> Failed

        assertTrue(m.state.value is StudyState.Failed, "was ${m.state.value}")

        // Retry: start() again with a fresh queue B. Stale card 2 must be gone.
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(9, "B9")), counts(new = 1))),
        )
        m.start()

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(9L, state.card.cardId) // queue B's first card, not A's leftover
    }

    // A serialized double-tap grade after an applied answer is a true no-op: the
    // second grade() (not ShowingBack, retainedBack cleared on success) posts
    // nothing and leaves the next card's ShowingFront untouched.
    @Test
    fun doubleGradeAfterSuccessIsNoop() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1))))
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()
        m.grade("good") // applied -> advance to card 2

        val afterFirst = m.state.value
        assertTrue(afterFirst is StudyState.ShowingFront, "was $afterFirst")
        assertEquals(2L, afterFirst.card.cardId)

        m.grade("good") // double-tap: not ShowingBack, retainedBack cleared -> no-op

        assertEquals(1, bridge.answerArgs.size) // answer() called exactly once
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(2L, state.card.cardId) // unchanged
    }

    // An "error" outcome keeps retainedBack so the legitimate re-grade path works.
    // (Same intent as errorResultFailsRetriableKeepingCard; asserts the retained
    // re-grade explicitly, guarding against over-clearing retainedBack.)
    @Test
    fun errorKeepsRetainedForRegrade() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(7, "s7")), counts(new = 1))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "error"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1", "u2"))
        m.start()
        m.reveal()
        m.grade("good") // error -> Failed(retriable), card retained

        assertTrue(m.state.value is StudyState.Failed, "was ${m.state.value}")

        // Re-grade without reveal succeeds against the retained card 7.
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u2", "applied"))))
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts())))
        m.grade("good")

        assertEquals(2, bridge.answerArgs.size)
        assertEquals(7L, bridge.answerArgs.last().single().cardId)
    }

    // Grading a card while the buffer is well above the prefetch threshold must
    // STILL refresh the visible counts from the engine: the engine reports fresh
    // counts on the post-grade fetch and the next ShowingFront reflects them.
    // (Regression: counts froze at session start until the buffer drained below 5.)
    @Test
    fun gradeRefreshesCountsWithFullBuffer() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 20), sync)))
        // 20 cards: buffer stays >= threshold after answering one -> no prefetch,
        // but counts must still refresh from the engine.
        val initial = (1L..20L).map { card(it, "s$it") }
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(initial, counts(new = 20))))
        // The post-grade counts-only fetch (limit=1) reports the ticked-down count.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(99, "s99")), counts(new = 19))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(19, state.counts.new) // counts ticked, not frozen at 20
    }

    // An "Again" that moves a new card to learning shows the updated learning
    // count on the very next emitted state, even with a full buffer.
    @Test
    fun againMovesNewToLearningOnNextState() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 20), sync)))
        val initial = (1L..20L).map { card(it, "s$it") }
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(initial, counts(new = 20))))
        // After "again": one new became a learning card.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(99, "s99")), counts(new = 19, learning = 1)),
            ),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("again")

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(19, state.counts.new)
        assertEquals(1, state.counts.learning) // moved to learning immediately
    }

    // The counts-only refresh must NOT pollute the buffer: grading with a full
    // buffer advances to the expected buffered card (card 2), and the card the
    // refresh fetch returned (card 99) is never surfaced. A second grade advances
    // to card 3 (still a buffered card, never the refresh's bait card 99).
    @Test
    fun countsRefreshDoesNotPolluteBuffer() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 20), sync)))
        val initial = (1L..20L).map { card(it, "s$it") }
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(initial, counts(new = 20))))
        // Each refresh fetch returns bait card 99; it must be ignored, not buffered.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(99, "s99")), counts(new = 19))),
        )
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(99, "s99")), counts(new = 18))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u2", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1", "u2"))
        m.start()
        m.reveal()

        m.grade("good") // advance off card 1
        val afterFirst = m.state.value
        assertTrue(afterFirst is StudyState.ShowingFront, "was $afterFirst")
        assertEquals(2L, afterFirst.card.cardId) // expected buffered card, not 99

        m.reveal()
        m.grade("good") // advance off card 2
        val afterSecond = m.state.value
        assertTrue(afterSecond is StudyState.ShowingFront, "was $afterSecond")
        assertEquals(3L, afterSecond.card.cardId) // still buffered order, not 99
    }

    // prefetch triggers a second queue() call when buffer drops below 5.
    @Test
    fun prefetchWhenBufferBelowFive() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 5), sync)))
        // 5 cards; after removing one -> 4 (<5) triggers prefetch.
        val initial = (1L..5L).map { card(it, "s$it") }
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(initial, counts(new = 5))))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(6, "s6")), counts(new = 4))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        assertEquals(1, bridge.queueCalls) // only the start fetch so far
        m.reveal()

        m.grade("good")

        assertEquals(2, bridge.queueCalls) // prefetch fired (single fetch, not doubled)
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(2L, state.card.cardId) // prefetched card 6 appended behind card 2
        assertEquals(4, state.counts.new) // prefetch also refreshed counts (5 -> 4)
    }

    // 401 mid-session -> Failed(retriable=false).
    @Test
    fun unauthorizedMidSessionFailsNonRetriable() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Fail(BridgeError.Unauthorized))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()

        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.Failed, "was $state")
        val cause = state.cause
        assertTrue(cause is FailCause.Transport, "was $cause")
        assertSame(BridgeError.Unauthorized, cause.error)
        assertTrue(!state.retriable)
    }

    // Unreachable during start -> Failed(retriable=true).
    @Test
    fun unreachableDuringStartFailsRetriable() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Fail(BridgeError.Unreachable))
        val m = machine(bridge)

        m.start()

        val state = m.state.value
        assertTrue(state is StudyState.Failed, "was $state")
        assertTrue(state.retriable)
    }

    // finish() calls studyFinish and yields Finished with its sync info.
    @Test
    fun finishSurfacesSyncInfo() = runBlocking {
        val bridge = FakeBridge()
        bridge.finishScript.add(FakeBridge.Outcome.Ok(SyncInfo(synced = true, detail = "synced")))
        val m = machine(bridge)

        m.finish()

        val state = m.state.value
        assertTrue(state is StudyState.Finished, "was $state")
        assertEquals("synced", state.sync?.detail)
    }

    // finish() best-effort: studyFinish failure still yields Finished(sync=null).
    @Test
    fun finishBestEffortOnFailure() = runBlocking {
        val bridge = FakeBridge()
        bridge.finishScript.add(FakeBridge.Outcome.Fail(BridgeError.Unreachable))
        val m = machine(bridge)

        m.finish()

        val state = m.state.value
        assertTrue(state is StudyState.Finished, "was $state")
        assertNull(state.sync)
    }

    // --- Undo (rslib's own undo, backend-computed) --------------------------------

    // answer a card -> undo -> the SAME card is shown again on its FRONT, with counts
    // matching the engine's post-undo counts. undo() is called and the engine re-queried.
    @Test
    fun undoBringsBackTheAnsweredCardOnItsFront() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        // Post-grade counts-only refresh (buffer 1 < 5 -> prefetch path).
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1))),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        m.reveal()
        m.grade("good") // advanced off card 1

        // The engine reverts the answer: card 1 returns to the top of the queue, counts restored.
        bridge.undoScript.add(FakeBridge.Outcome.Ok(UndoResult(undone = true, undoableAnswer = false)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )

        m.undo()

        assertEquals(1, bridge.undoCalls, "undo must call the engine's own undo")
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state") // FRONT side, not back
        assertEquals(1L, state.card.cardId) // the SAME card returns
        assertEquals(2, state.counts.new) // counts ticked back to the pre-answer value
    }

    // undo with nothing to undo must be safe: no crash, state uncorrupted, and it does
    // NOT re-query the queue (nothing changed).
    @Test
    fun undoWithNothingToUndoIsSafe() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        val m = machine(bridge)
        m.start()
        val before = m.state.value

        bridge.undoScript.add(FakeBridge.Outcome.Ok(UndoResult(undone = false)))
        m.undo()

        assertEquals(1, bridge.undoCalls)
        assertEquals(1, bridge.queueCalls, "no re-query when nothing was undone")
        val after = m.state.value
        assertTrue(after is StudyState.ShowingFront, "was $after")
        assertEquals(1L, after.card.cardId) // unchanged
    }

    // answer -> undo -> answer again works: the buffer is coherent (no duplicate/skipped
    // cards), and the second answer targets the card the undo brought back.
    @Test
    fun answerUndoAnswerAgainKeepsBufferCoherent() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1))))
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1", "u2"))
        m.start()
        m.reveal()
        m.grade("good") // off card 1

        // Undo: card 1 comes back at the top.
        bridge.undoScript.add(FakeBridge.Outcome.Ok(UndoResult(undone = true)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        m.undo()
        assertEquals(1L, (m.state.value as StudyState.ShowingFront).card.cardId)

        // Answer card 1 AGAIN: it grades cleanly and advances to card 2 (no dup, no skip).
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u2", "applied"))))
        bridge.queueScript.add(FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1))))
        m.reveal()
        m.grade("good")

        assertEquals(1L, bridge.answerArgs.last().single().cardId) // re-answered card 1
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(2L, state.card.cardId) // advanced to card 2, not a duplicate of 1
    }

    // The UNDO control gate: ShowingFront exposes undoAvailable = true only once an
    // answer was given this session AND the engine reports an undoable op. Before any
    // answer it is false (queue reports undoableAnswer = false).
    @Test
    fun undoAvailableTracksEngineAndSessionAnswer() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(
                    listOf(card(1, "s1"), card(2, "s2")),
                    counts(new = 2),
                    undoableAnswer = false,
                ),
            ),
        )
        val m = machine(bridge, now = clock(1_000L, 1_200L), uuid = uuids("u1"))
        m.start()
        // No answer yet -> the control must be hidden.
        assertEquals(false, (m.state.value as StudyState.ShowingFront).undoAvailable)

        // After answering, the post-grade fetch reports an undoable op.
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(emptyList(), counts(new = 1), undoableAnswer = true)),
        )
        bridge.answerScript.add(FakeBridge.Outcome.Ok(listOf(AnswerResult("u1", "applied"))))
        m.reveal()
        m.grade("good")

        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(true, state.undoAvailable) // answered this session AND engine has an undoable op
    }

    // --- Card actions: bury / suspend / mark --------------------------------------

    // Bury the current card: the engine's buryCard is called with the current card id,
    // the buried card is dropped, the machine advances to the next card, and counts are
    // refreshed from a fresh engine re-query (the buried card must NOT reappear).
    @Test
    fun buryDropsCurrentAdvancesAndRefreshesCounts() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        val m = machine(bridge)
        m.start()

        // The engine buries card 1, then the fresh re-query returns only card 2, counts down.
        bridge.buryScript.add(FakeBridge.Outcome.Ok(Unit))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(2, "s2")), counts(new = 1))),
        )

        m.buryCurrent()

        assertEquals(listOf(1L), bridge.buryArgs) // buried the current card
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state") // advanced, on front
        assertEquals(2L, state.card.cardId) // the buried card 1 is gone; card 2 shown
        assertEquals(1, state.counts.new) // counts refreshed from the engine
    }

    // Suspend the current card: same shape as bury (engine op + advance + re-query).
    @Test
    fun suspendDropsCurrentAdvancesAndRefreshesCounts() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 2), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(
                QueueResponse(listOf(card(1, "s1"), card(2, "s2")), counts(new = 2)),
            ),
        )
        val m = machine(bridge)
        m.start()

        bridge.suspendScript.add(FakeBridge.Outcome.Ok(Unit))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(2, "s2")), counts(new = 1))),
        )

        m.suspendCurrent()

        assertEquals(listOf(1L), bridge.suspendArgs)
        val state = m.state.value
        assertTrue(state is StudyState.ShowingFront, "was $state")
        assertEquals(2L, state.card.cardId)
        assertEquals(1, state.counts.new)
    }

    // Mark the current note: state reflects nowMarked, the SAME card stays shown (no
    // advance, no re-query), toggling again unmarks it.
    @Test
    fun markTogglesStateWithoutAdvancing() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        val m = machine(bridge)
        m.start()
        assertEquals(false, (m.state.value as StudyState.ShowingFront).marked)

        bridge.markScript.add(FakeBridge.Outcome.Ok(true))
        m.toggleMarkCurrent()

        assertEquals(listOf(10L), bridge.markArgs) // toggled the current note (id 1*10)
        assertEquals(1, bridge.queueCalls) // no re-query: mark does not advance
        val marked = m.state.value
        assertTrue(marked is StudyState.ShowingFront, "was $marked")
        assertEquals(1L, marked.card.cardId) // same card still shown
        assertEquals(true, marked.marked) // indicator on

        // Toggle again -> unmarked.
        bridge.markScript.add(FakeBridge.Outcome.Ok(false))
        m.toggleMarkCurrent()
        val unmarked = m.state.value
        assertTrue(unmarked is StudyState.ShowingFront, "was $unmarked")
        assertEquals(false, unmarked.marked)
    }

    // Mark works from the revealed (back) side too, staying on the back without advancing.
    @Test
    fun markTogglesFromBackSideWithoutAdvancing() = runBlocking {
        val bridge = FakeBridge()
        bridge.startScript.add(FakeBridge.Outcome.Ok(StudyStartResponse(counts(new = 1), sync)))
        bridge.queueScript.add(
            FakeBridge.Outcome.Ok(QueueResponse(listOf(card(1, "s1")), counts(new = 1))),
        )
        val m = machine(bridge, now = clock(1_000L))
        m.start()
        m.reveal()

        bridge.markScript.add(FakeBridge.Outcome.Ok(true))
        m.toggleMarkCurrent()

        val state = m.state.value
        assertTrue(state is StudyState.ShowingBack, "was $state") // still on the back
        assertEquals(1L, state.card.cardId)
        assertEquals(true, state.marked)
        assertEquals(1_000L, state.shownAtMs) // reveal time preserved
    }
}
