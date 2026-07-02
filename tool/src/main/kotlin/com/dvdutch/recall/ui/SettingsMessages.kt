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
            "can't reach your bridge at $url"
        is BridgeError.Unauthorized ->
            "token rejected"
        is BridgeError.NeedsAttention ->
            "bridge needs attention: full sync required (fix on the server)"
        is BridgeError.VersionSkew ->
            "update the tool / update the bridge"
        is BridgeError.Server ->
            error.message
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
