package com.dvdutch.recall.api

/**
 * The subset of [BridgeClient] operations a study session needs.
 *
 * Extracted so [com.dvdutch.recall.study.StudyMachine] can be unit-tested on the
 * JVM against an in-memory fake instead of a real HTTP client. [BridgeClient]
 * implements this interface unchanged; production code passes a [BridgeClient]
 * exactly where the binding contract expects one (a `BridgeClient` *is* a
 * `BridgeApi`), so no caller is affected.
 *
 * Each method mirrors its [BridgeClient] counterpart's signature verbatim,
 * including the same [BridgeError] failure semantics.
 */
interface BridgeApi {
    suspend fun studyStart(deckId: Long): StudyStartResponse
    suspend fun queue(limit: Int = 20): QueueResponse
    suspend fun answer(answers: List<AnswerIn>): List<AnswerResult>
    suspend fun studyFinish(): SyncInfo
}
