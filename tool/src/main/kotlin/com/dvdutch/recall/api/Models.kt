package com.dvdutch.recall.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for parsing bridge responses.
 *
 * `ignoreUnknownKeys` keeps us forward-compatible when the bridge adds fields
 * to a payload we already know about.
 */
val BridgeJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class Deck(
    val id: Long,
    val name: String,
    val new: Int,
    val learning: Int,
    val review: Int,
)

@Serializable
data class DecksResponse(val decks: List<Deck>)

@Serializable
data class Counts(val new: Int, val learning: Int, val review: Int)

@Serializable
data class QueueResponse(
    val cards: List<CardPayload>,
    val counts: Counts,
    // True when the engine reports an undoable operation on its stack (the last
    // answer, in a review-only session). Additive to the frozen contract with a
    // safe default, so older callers/tests and JSON payloads lacking it stay valid.
    // The StudyMachine uses it (AND an answered-this-session flag) to gate the
    // UNDO control — mirrors AnkiDroid enabling Undo only when the backend has an
    // undoable op.
    @SerialName("undoable_answer") val undoableAnswer: Boolean = false,
)

/**
 * The outcome of a [EngineApi.undo] call: rslib's own undo (the same op
 * AnkiDroid's toolbar Undo drives), never a local reconstruction. [undone] is
 * false when there was nothing on the undo stack to revert (a safe no-op).
 * [undoableAnswer] reports whether, AFTER this undo, the engine still has an
 * undoable op — so the UI can keep the UNDO control visible for a further tap
 * (multi-step) or hide it once the stack is exhausted.
 */
@Serializable
data class UndoResult(
    val undone: Boolean,
    @SerialName("undoable_answer") val undoableAnswer: Boolean = false,
)

@Serializable
data class CardPayload(
    @SerialName("card_id") val cardId: Long,
    @SerialName("note_id") val noteId: Long,
    val front: List<RenderNode>,
    val back: List<RenderNode>,
    val states: String,
    @SerialName("next_due_labels") val nextDueLabels: Map<String, String>,
    // Ordered [sound:] media filenames per side (empty when the side has no audio).
    // Additive to the frozen EngineApi contract: StudyMachine ignores these, and the
    // defaults keep older callers/tests valid.
    @SerialName("front_audio") val frontAudio: List<String> = emptyList(),
    @SerialName("back_audio") val backAudio: List<String> = emptyList(),
    // Whether the card's note currently carries the "marked" tag (AnkiDroid's Mark Note).
    // Additive with a safe default: the StudyMachine surfaces it as a subtle indicator and
    // toggling it via toggleMark never advances the card. Older payloads lacking it read
    // false (unmarked).
    val marked: Boolean = false,
)

@Serializable
data class AnswerIn(
    val uuid: String,
    @SerialName("card_id") val cardId: Long,
    val rating: String,
    val states: String,
    @SerialName("ms_taken") val msTaken: Long,
    @SerialName("answered_at") val answeredAt: Long,
)

@Serializable
data class AnswerResult(val uuid: String, val status: String)

@Serializable
data class StatusResponse(
    @SerialName("bridge_version") val bridgeVersion: String,
    @SerialName("anki_version") val ankiVersion: String,
    @SerialName("collection_open") val collectionOpen: Boolean,
    @SerialName("last_sync") val lastSync: Long? = null,
    @SerialName("needs_attention") val needsAttention: Boolean = false,
)

@Serializable
data class SyncInfo(val synced: Boolean, val detail: String)

@Serializable
data class StudyStartResponse(val counts: Counts, val sync: SyncInfo)
