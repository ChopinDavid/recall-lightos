package com.dvdutch.recall.api

/**
 * The subset of engine operations a study session needs.
 *
 * Extracted so [com.dvdutch.recall.study.StudyMachine] can be unit-tested on the
 * JVM against an in-memory fake instead of a real engine.
 * [com.dvdutch.recall.engine.LocalEngineApi] is the production implementation,
 * driving rslib on-device; failures surface as [BridgeError].
 */
interface EngineApi {
    suspend fun studyStart(deckId: Long): StudyStartResponse
    suspend fun queue(limit: Int = 20): QueueResponse
    suspend fun answer(answers: List<AnswerIn>): List<AnswerResult>
    suspend fun studyFinish(): SyncInfo

    /**
     * Reverts the last operation via rslib's OWN undo (the same op AnkiDroid's
     * toolbar Undo drives) — NEVER a local reconstruction. In a review-only
     * session the top op is the last answer, so this un-answers the last card:
     * it returns to the queue and the due counts tick back. Idempotent-safe: an
     * empty undo stack yields [UndoResult]`(undone = false)` rather than throwing.
     */
    suspend fun undo(): UndoResult

    /**
     * Buries [cardId] via rslib's own bury (the same op AnkiDroid's Bury Card drives) —
     * NEVER local logic. The card leaves the study queue until tomorrow and the change
     * syncs. A backend op with no return: the caller re-queries the queue to observe the
     * card's removal and the ticked-down counts.
     */
    suspend fun buryCard(cardId: Long)

    /**
     * Suspends [cardId] via rslib's own suspend (the same op AnkiDroid's Suspend Card
     * drives) — NEVER local logic. The card leaves the study queue until it is unsuspended
     * (on desktop), and the change syncs. A backend op with no return: the caller
     * re-queries the queue to observe the removal and the ticked-down counts.
     */
    suspend fun suspendCard(cardId: Long)

    /**
     * Toggles the "marked" tag on [noteId]'s note via rslib (the same op AnkiDroid's Mark
     * Note drives) — NEVER local logic. Returns whether the note is NOW marked (true) or
     * unmarked (false) after the toggle. The change syncs; the card stays in the queue.
     */
    suspend fun toggleMark(noteId: Long): Boolean

    /**
     * Computes the type-answer grading diff via rslib's OWN `compareAnswer` (the same
     * comparison AnkiDroid/desktop drive) — NEVER a local diff. Returns rslib's diff
     * HTML (`<code id=typeans>…</code>` with typeGood/typeBad/typeMissed spans), which
     * [com.dvdutch.recall.engine.parseTypeAnswerDiff] turns into styled render nodes.
     *
     * [expected] is the resolved expected answer ([CardPayload.typeAnswerExpected]);
     * [provided] is what the user typed. When [noCase] is true (an `[[type:nc:Field]]`
     * marker) both sides are lowercased before comparison, so the diff is case-insensitive.
     */
    suspend fun compareTypedAnswer(expected: String, provided: String, noCase: Boolean = false): String
}
