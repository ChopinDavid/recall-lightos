package com.dvdutch.recall.study

import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.EngineApi
import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The observable state of a study session.
 *
 * The machine only ever exposes one of these; the UI renders whichever is
 * current. [ShowingBack] carries the reveal timestamp so grading can compute a
 * deterministic `ms_taken` without reaching for a global clock.
 */
sealed interface StudyState {
    /** Session is starting: `studyStart` + first `queue` fetch in flight. */
    data object Loading : StudyState

    /** A card's front is up; [counts] reflect the most recent server report. */
    data class ShowingFront(val card: CardPayload, val counts: Counts) : StudyState

    /** The back is revealed; [shownAtMs] is when the reveal happened. */
    data class ShowingBack(val card: CardPayload, val counts: Counts, val shownAtMs: Long) : StudyState

    /**
     * No cards remain NOW (or the session was finished); [reviewed] answers
     * counted. [counts] carries the most recent server report so the UI can
     * distinguish "all done" from "done for now — more due later today"
     * (e.g. `counts.learning > 0` with the queue empty).
     */
    data class Finished(
        val reviewed: Int,
        val sync: com.dvdutch.recall.api.SyncInfo?,
        val counts: Counts?,
    ) : StudyState

    /** A bridge call failed. [retriable] distinguishes transient from terminal. */
    data class Failed(val cause: FailCause, val retriable: Boolean) : StudyState
}

/**
 * Why a [StudyState.Failed] happened, kept distinct so the UI renders the right
 * copy: a transport-level [BridgeError] is not the same as the bridge accepting
 * the request but rejecting one answer.
 */
sealed interface FailCause {
    /** A bridge call failed at the transport level; [error] is the taxonomy. */
    data class Transport(val error: BridgeError) : FailCause

    /** The bridge returned a per-item `"error"` status for the graded answer. */
    data object AnswerRejected : FailCause
}

/**
 * The pure-Kotlin study-session state machine.
 *
 * This is the only component that constructs [AnswerIn]s, and it does so under
 * strict rules: `states` is echoed byte-for-byte from the [CardPayload] the
 * server sent, `ms_taken` is `nowMs()` at grade minus the reveal timestamp, and
 * the answer `uuid` comes from the injected [uuid] generator. It NEVER inspects
 * or reinterprets scheduling data.
 *
 * All I/O goes through [EngineApi] (implemented on-device by
 * [com.dvdutch.recall.engine.LocalEngineApi]) so the machine is unit-testable on
 * the JVM with no Android or engine dependencies. [nowMs] and [uuid] are injected
 * to keep every transition deterministic under test.
 *
 * The buffer is topped up eagerly: whenever it drops below [PREFETCH_THRESHOLD]
 * cards a second `queue()` fetch is appended. The session is [StudyState.Finished]
 * once the buffer is exhausted and the server reports no cards remain.
 */
class StudyMachine(
    private val client: EngineApi,
    private val deckId: Long,
    private val nowMs: () -> Long,
    private val uuid: () -> String,
) {
    private val _state = MutableStateFlow<StudyState>(StudyState.Loading)
    val state: StateFlow<StudyState> = _state.asStateFlow()

    /** Local buffer of cards not yet answered; the head is the current card. */
    private val buffer = ArrayDeque<CardPayload>()

    /** Most recent counts the server reported (via start or a queue fetch). */
    private var counts: Counts = Counts(0, 0, 0)

    /** Answers whose result counted (`applied`/`duplicate`) toward review. */
    private var reviewed: Int = 0

    /** Recorded at reveal so grading can compute a deterministic `ms_taken`. */
    private var shownAtMs: Long = 0L

    /**
     * The revealed card retained across a retriable grade failure, so a follow-up
     * [grade] call can re-post the same answer. Cleared once a grade advances.
     */
    private var retainedBack: StudyState.ShowingBack? = null

    /**
     * Begins (or restarts) the session: `studyStart` then the first `queue` fetch.
     * Idempotent restart semantics — a retry after a mid-session failure clears the
     * stale [buffer] and any [retainedBack] first, then fetches fresh, so leftover
     * cards from the prior attempt never stack. [reviewed] is deliberately NOT
     * reset: applied reviews really happened and persist across a restart.
     *
     * Emits [StudyState.ShowingFront] for the first card, [StudyState.Finished] if
     * none were returned, or [StudyState.Failed] on any bridge error.
     */
    suspend fun start() {
        _state.value = StudyState.Loading
        buffer.clear()
        retainedBack = null
        guard {
            client.studyStart(deckId)
            val response = client.queue()
            buffer.addAll(response.cards)
            counts = response.counts
            settle()
        }
    }

    /**
     * Reveals the current card's back, recording the reveal time. No-op unless we
     * are currently [StudyState.ShowingFront].
     */
    fun reveal() {
        val current = _state.value
        if (current !is StudyState.ShowingFront) return
        shownAtMs = nowMs()
        _state.value = StudyState.ShowingBack(current.card, current.counts, shownAtMs)
    }

    /**
     * Grades the current (revealed) card with [rating], posting an [AnswerIn]
     * whose `states` is echoed byte-identically. On `applied`/`duplicate` the
     * review counter advances; on `stale`/`gone` the card is dropped without
     * counting; on `error` the machine keeps the card and reports
     * [StudyState.Failed]`(retriable = true)` so the grade can be retried.
     *
     * No-op unless we are currently [StudyState.ShowingBack].
     */
    suspend fun grade(rating: String) {
        // Grade the revealed card, or the one retained from a prior retriable failure.
        val back = _state.value as? StudyState.ShowingBack ?: retainedBack ?: return
        val card = back.card

        val answeredAt = nowMs()
        val answer = AnswerIn(
            uuid = uuid(),
            cardId = card.cardId,
            rating = rating,
            states = card.states, // echoed byte-for-byte; never reinterpreted
            msTaken = answeredAt - back.shownAtMs,
            answeredAt = answeredAt,
        )

        // Retain the card up front so ANY retriable failure (transport or `error`
        // result) leaves it available to re-grade; cleared once a grade advances.
        retainedBack = back
        guard {
            val result = client.answer(listOf(answer)).firstOrNull()?.status
            if (result == "error") {
                // Keep the card retained so the grade can be re-posted; surface
                // retriable. This is the ONLY outcome that preserves retainedBack.
                _state.value = StudyState.Failed(FailCause.AnswerRejected, retriable = true)
                return@guard
            }
            // Every terminal outcome (applied/duplicate/stale/gone/unknown) clears
            // the retained card, so a serialized double-tap grade becomes a true
            // no-op instead of re-posting the previous card as a ghost answer.
            retainedBack = null
            when (result) {
                // applied / duplicate: the review counted.
                "applied", "duplicate" -> reviewed++
                // stale / gone / unknown: superseded, advance without counting.
                else -> Unit
            }
            advance()
        }
    }

    /**
     * Ends the session, calling `studyFinish` best-effort. Always transitions to
     * [StudyState.Finished]: on success it carries the returned sync info, on any
     * failure it carries `sync = null`.
     */
    suspend fun finish() {
        val sync = try {
            client.studyFinish()
        } catch (_: BridgeError) {
            null
        }
        _state.value = StudyState.Finished(reviewed, sync, counts)
    }

    /**
     * Drops the answered head card, tops up the buffer when it falls below
     * [PREFETCH_THRESHOLD], and refreshes [counts] from the engine on EVERY grade
     * so the study-screen header ticks live instead of freezing at session-start
     * values until the buffer drains. Then re-derives the visible state.
     *
     * Two distinct fetch paths, one of which always runs:
     *   - Prefetch (buffer below threshold): a full `queue()` whose cards refill
     *     the buffer AND whose counts refresh the header — unchanged behaviour.
     *   - Counts-only refresh (buffer still full): a `queue(limit = 1)` from which
     *     we take ONLY `.counts`; its card(s) are deliberately DISCARDED so they
     *     never duplicate or reorder the still-buffered cards.
     *
     * This mirrors AnkiDroid, which re-queries `getQueuedCards(fetchLimit = 1)`
     * after every answer to keep its counts current. On-device this is an
     * in-process JNI call (microseconds), so refreshing every grade is cheap;
     * `queue(1)` compiles a single card payload we throw away, an acceptable cost
     * that avoids widening the frozen [EngineApi] contract with a counts-only
     * method. The card BUFFER cadence is unchanged: we do NOT fetch cards more
     * often, and [PREFETCH_THRESHOLD] is untouched.
     */
    private suspend fun advance() {
        if (buffer.isNotEmpty()) buffer.removeFirst()
        if (buffer.size < PREFETCH_THRESHOLD) {
            val response = client.queue()
            buffer.addAll(response.cards)
            counts = response.counts
        } else {
            // Buffer still full: refresh ONLY the counts, discarding fetched cards
            // so they cannot pollute (duplicate/reorder) the buffer.
            counts = client.queue(limit = 1).counts
        }
        settle()
    }

    /**
     * Emits [StudyState.ShowingFront] for the head card, or [StudyState.Finished]
     * when the buffer is exhausted (session complete). Grading resets to the
     * front of the next card by design.
     */
    private fun settle() {
        val next = buffer.firstOrNull()
        _state.value = if (next == null) {
            // Empty NOW, but carry the latest counts so the UI can say whether
            // more are due later today. No re-polling: anti-loop behavior intact.
            StudyState.Finished(reviewed, sync = null, counts = counts)
        } else {
            StudyState.ShowingFront(next, counts)
        }
    }

    /**
     * Runs [block], mapping any [BridgeError] onto [StudyState.Failed]:
     * [BridgeError.Unauthorized] is terminal (`retriable = false`); every other
     * error is transient (`retriable = true`).
     */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: BridgeError) {
            _state.value = StudyState.Failed(
                FailCause.Transport(e),
                retriable = e !is BridgeError.Unauthorized,
            )
        }
    }

    private companion object {
        /** Refill the buffer once it holds fewer than this many cards. */
        const val PREFETCH_THRESHOLD = 5
    }
}
