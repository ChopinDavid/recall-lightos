package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure first-run state-machine tests. The first-run flow is:
 * `Intro → (config entered) → Downloading → Done | Failed`, with a retry from
 * Failed back to Downloading. The transitions live in a Compose-free reducer so
 * they are unit-testable; the screen only renders whichever [FirstRunPhase] is
 * current and fires the side effects (login, fullSync).
 */
class FirstRunStateTest {

    private val config = com.dvdutch.recall.engine.SyncConfig(
        endpoint = "http://10.0.2.2:18080/",
        username = "test",
        password = "test123",
    )

    @Test
    fun `starts on the intro phase with the default endpoint prefilled`() {
        val state = FirstRunState.initial(defaultEndpoint = "http://10.0.2.2:18080/")
        assertTrue(state.phase is FirstRunPhase.Intro)
        assertEquals("http://10.0.2.2:18080/", state.endpoint)
        assertEquals("", state.username)
        assertEquals("", state.password)
    }

    @Test
    fun `canDownload only when all three fields are non-blank`() {
        val base = FirstRunState.initial("e")
        assertFalse(base.canDownload, "username + password still blank")
        val noEndpoint = base.copy(endpoint = "", username = "test", password = "pw")
        assertFalse(noEndpoint.canDownload, "blank endpoint blocks download")
        val noPassword = base.copy(username = "test", password = "")
        assertFalse(noPassword.canDownload, "blank password blocks download")
        val full = base.copy(username = "test", password = "pw")
        assertTrue(full.canDownload)
    }

    @Test
    fun `starting the download enters the downloading phase and carries the config`() {
        val state = FirstRunState.initial("http://10.0.2.2:18080/")
            .copy(username = "test", password = "test123")
        val next = state.startDownloading()
        assertTrue(next.phase is FirstRunPhase.Downloading)
        assertEquals(config, next.syncConfig())
    }

    @Test
    fun `a successful download lands on done`() {
        val state = FirstRunState.initial("e").copy(username = "u", password = "p").startDownloading()
        assertTrue(state.succeeded().phase is FirstRunPhase.Done)
    }

    @Test
    fun `a failed download surfaces the reason and allows retry`() {
        val state = FirstRunState.initial("e").copy(username = "u", password = "p").startDownloading()
        val failed = state.failed("network unreachable")
        val phase = failed.phase
        assertTrue(phase is FirstRunPhase.Failed)
        assertEquals("network unreachable", phase.reason)
        // Retry re-enters Downloading with the same config preserved.
        val retried = failed.startDownloading()
        assertTrue(retried.phase is FirstRunPhase.Downloading)
        assertEquals("u", retried.username)
    }
}
