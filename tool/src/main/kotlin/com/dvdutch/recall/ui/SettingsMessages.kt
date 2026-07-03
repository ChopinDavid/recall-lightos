package com.dvdutch.recall.ui

import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.StatusResponse

/**
 * Pure, Compose-free mapping from a "Test connection" outcome to the single
 * status line shown under the Settings fields. Kept out of any Composable so it
 * is unit-testable on the JVM without an Android/Compose runtime.
 */
object SettingsMessages {

    /**
     * Success line: `ok — anki <version> · synced <when>` where `<when>` is a
     * relative label ([relativeSince]) or `never` when the bridge reports no
     * prior sync.
     */
    fun okLine(status: StatusResponse, now: Long = System.currentTimeMillis()): String {
        val syncedWhen = status.lastSync?.let { relativeSince(it, now) } ?: "never"
        return "ok — anki ${status.ankiVersion} · synced $syncedWhen"
    }

    /**
     * Maps every [BridgeError] variant to operator-facing copy. `url` is the
     * user's configured bridge URL, interpolated into the unreachable message.
     */
    fun errorLine(error: BridgeError, url: String): String = when (error) {
        is BridgeError.Unreachable ->
            "sync failed — check your connection to $url"
        is BridgeError.Unauthorized ->
            "token rejected"
        is BridgeError.NeedsAttention ->
            "needs attention: full sync required (resolve from the home screen)"
    }

    /** Success line for the Settings "Test login" action. */
    fun loginOkLine(): String = "login ok"

    /**
     * Failure line for "Test login": `login failed: <reason>`, collapsing to a
     * bare `login failed` when the engine gave no usable message.
     */
    fun loginFailedLine(reason: String?): String {
        val trimmed = reason?.trim().orEmpty()
        return if (trimmed.isEmpty()) "login failed" else "login failed: $trimmed"
    }

    /**
     * The Settings last-sync line: `last sync: <when>` where `<when>` is a
     * relative label ([relativeSince]) or `never` when no clean sync has happened.
     */
    fun lastSyncLine(lastSyncMillis: Long?, now: Long = System.currentTimeMillis()): String {
        val whenLabel = lastSyncMillis?.let { relativeSince(it, now) } ?: "never"
        return "last sync: $whenLabel"
    }

    /**
     * A coarse relative label for a past epoch-millis instant: `just now`,
     * `<n>m ago`, `<n>h ago`, `<n>d ago`. Future/zero deltas collapse to
     * `just now`. This is intentionally simple; the connection test only needs a
     * human-legible "when".
     */
    fun relativeSince(thenMillis: Long, nowMillis: Long): String {
        val deltaMs = nowMillis - thenMillis
        if (deltaMs < 60_000L) return "just now"
        val minutes = deltaMs / 60_000L
        if (minutes < 60L) return "${minutes}m ago"
        val hours = minutes / 60L
        if (hours < 24L) return "${hours}h ago"
        val days = hours / 24L
        return "${days}d ago"
    }
}
