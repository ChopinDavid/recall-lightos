package com.dvdutch.recall.ui

import com.dvdutch.recall.engine.SyncConfig

/** The four phases of the first-run download flow. */
sealed interface FirstRunPhase {
    /** Explaining what's about to happen and collecting sync config. */
    data object Intro : FirstRunPhase

    /** A full download (`fullSync(upload=false)`) is in flight. */
    data object Downloading : FirstRunPhase

    /** The collection downloaded; the screen navigates on to Home. */
    data object Done : FirstRunPhase

    /** The download failed; [reason] is shown and a retry is offered. */
    data class Failed(val reason: String) : FirstRunPhase
}

/**
 * Pure, Compose-free state for the first-run flow. The screen renders whichever
 * [phase] is current and fires the side effects (login + `fullSync(upload=false)`);
 * every transition is a total function here so they are unit-testable without a
 * Compose/engine runtime.
 */
data class FirstRunState(
    val phase: FirstRunPhase,
    val endpoint: String,
    val username: String,
    val password: String,
) {
    /** Masks the password — data-class toString would leak it into any log. */
    override fun toString(): String =
        "FirstRunState(endpoint=$endpoint, username=$username, password=***, phase=$phase)"

    /** True only when all three sync-config fields are non-blank (mirrors [SyncController.configured]). */
    val canDownload: Boolean
        get() = endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** The sync config assembled from the entered fields. */
    fun syncConfig(): SyncConfig = SyncConfig(endpoint.trim(), username.trim(), password)

    /** Enter (or re-enter, on retry) the download phase. */
    fun startDownloading(): FirstRunState = copy(phase = FirstRunPhase.Downloading)

    /** The download completed; the collection is present locally. */
    fun succeeded(): FirstRunState = copy(phase = FirstRunPhase.Done)

    /** The download failed with [reason]; a retry re-enters [startDownloading]. */
    fun failed(reason: String): FirstRunState = copy(phase = FirstRunPhase.Failed(reason))

    companion object {
        /** Initial state: on [FirstRunPhase.Intro] with the endpoint prefilled. */
        fun initial(defaultEndpoint: String): FirstRunState = FirstRunState(
            phase = FirstRunPhase.Intro,
            endpoint = defaultEndpoint,
            username = "",
            password = "",
        )
    }
}
