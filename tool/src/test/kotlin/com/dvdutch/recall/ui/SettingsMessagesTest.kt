package com.dvdutch.recall.ui

import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.StatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [SettingsMessages] — the pure error/success -> status line
 * mapping shown by the Settings "Test connection" action. No Compose/Android
 * runtime is required; the mapping lives in a plain object so it is testable on
 * the JVM.
 */
class SettingsMessagesTest {

    private val url = "http://10.0.2.2:8000"

    @Test
    fun `unreachable interpolates the configured url`() {
        assertEquals(
            "sync failed — check your connection to $url",
            SettingsMessages.errorLine(BridgeError.Unreachable, url),
        )
    }

    @Test
    fun `unauthorized maps to token rejected`() {
        assertEquals(
            "token rejected",
            SettingsMessages.errorLine(BridgeError.Unauthorized, url),
        )
    }

    @Test
    fun `needs attention maps to full sync copy`() {
        assertEquals(
            "needs attention: full sync required (resolve from the home screen)",
            SettingsMessages.errorLine(BridgeError.NeedsAttention, url),
        )
    }

    @Test
    fun `ok line with never-synced reports never`() {
        val status = StatusResponse(
            bridgeVersion = "0.1.0",
            ankiVersion = "25.09.5",
            collectionOpen = true,
            lastSync = null,
        )
        assertEquals(
            "ok — anki 25.09.5 · synced never",
            SettingsMessages.okLine(status, now = 1_000_000L),
        )
    }

    @Test
    fun `ok line with recent sync reports just now`() {
        val now = 1_000_000L
        val status = StatusResponse(
            bridgeVersion = "0.1.0",
            ankiVersion = "25.09.5",
            collectionOpen = true,
            lastSync = now - 5_000L,
        )
        assertEquals(
            "ok — anki 25.09.5 · synced just now",
            SettingsMessages.okLine(status, now = now),
        )
    }

    @Test
    fun `login ok line reports success`() {
        assertEquals("login ok", SettingsMessages.loginOkLine())
    }

    @Test
    fun `login failed line carries the reason`() {
        assertEquals(
            "login failed: wrong password",
            SettingsMessages.loginFailedLine("wrong password"),
        )
    }

    @Test
    fun `login failed line falls back when the reason is blank`() {
        assertEquals("login failed", SettingsMessages.loginFailedLine("  "))
        assertEquals("login failed", SettingsMessages.loginFailedLine(null))
    }

    @Test
    fun `last-sync line reports never when there is no prior sync`() {
        assertEquals("last sync: never", SettingsMessages.lastSyncLine(null, now = 1_000_000L))
    }

    @Test
    fun `last-sync line reports a relative label`() {
        val now = 100_000_000L
        assertEquals(
            "last sync: 5m ago",
            SettingsMessages.lastSyncLine(now - 5 * 60_000L, now = now),
        )
    }

    @Test
    fun `relative labels cover minutes hours and days`() {
        val now = 100_000_000L
        assertEquals("just now", SettingsMessages.relativeSince(now - 30_000L, now))
        assertEquals("5m ago", SettingsMessages.relativeSince(now - 5 * 60_000L, now))
        assertEquals("3h ago", SettingsMessages.relativeSince(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", SettingsMessages.relativeSince(now - 2 * 86_400_000L, now))
    }
}
