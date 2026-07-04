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
}
