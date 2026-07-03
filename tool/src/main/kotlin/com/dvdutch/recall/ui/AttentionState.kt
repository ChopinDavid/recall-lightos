package com.dvdutch.recall.ui

/**
 * A direction for resolving a needs-attention (FULL_*) divergence via a full,
 * one-way collection transfer. [upload] is the argument passed straight to
 * `SyncController.fullSync(upload)`; [word] is the exact confirmation the operator
 * must type before it fires.
 */
enum class AttentionDirection(val word: String, val upload: Boolean) {
    /** Pull the server's collection down, discarding local divergence. */
    Download("download", upload = false),

    /** Push the local collection up, discarding the server's divergence. */
    Upload("upload", upload = true),
}

/**
 * Pure confirmation matcher for the needs-attention screen. Resolving a FULL_*
 * divergence overwrites one side's history, so — like the bridge CLI — the
 * operator must type the exact direction word before the destructive
 * `fullSync(direction)` runs. Matching is trimmed and case-insensitive so an
 * on-screen-keyboard capital or stray space doesn't reject an intended confirm.
 */
object AttentionConfirm {

    /** True when [typed] is the exact confirmation word for [direction]. */
    fun matches(direction: AttentionDirection, typed: String): Boolean =
        typed.trim().equals(direction.word, ignoreCase = true)

    /** Resolves a typed word to its [AttentionDirection], or null if it is neither. */
    fun parse(typed: String): AttentionDirection? {
        val normalized = typed.trim().lowercase()
        return AttentionDirection.entries.firstOrNull { it.word == normalized }
    }
}
