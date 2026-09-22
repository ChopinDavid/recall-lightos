package com.dvdutch.recall.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wiring tests for the two entry points into [SyncSetupScreen].
 *
 * The screens in this module are `LightScreen` subclasses whose navigation runs
 * through the SDK's activity-backed `navigateTo` — there is no Robolectric or
 * Compose-test dependency in `:tool` (see tool/build.gradle.kts), so a real
 * click-and-assert test is not available on the JVM. What IS worth guarding, and
 * what actually broke this feature if it regressed, is that both entry points
 * exist at all: the Light Phone III has no browser, so a help screen with no row
 * pointing at it is help no device user can reach.
 *
 * These read the screen sources and assert the row + navigateTo pair is present.
 * Coarse, but it fails loudly if someone deletes a row, and it pairs with the
 * on-emulator gates that exercise the real taps.
 */
class SyncSetupNavWiringTest {

    private fun source(name: String): String {
        // Tests run with the module dir as the working directory.
        val file = File("src/main/kotlin/com/dvdutch/recall/ui/$name")
        assertTrue(file.isFile, "expected to find ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `first-run screen navigates to the sync setup screen`() {
        val src = source("FirstRunScreen.kt")
        assertTrue(
            src.contains("navigateTo(::SyncSetupScreen)"),
            "FirstRunScreen must navigate to SyncSetupScreen",
        )
    }

    @Test
    fun `first-run screen renders the help row label`() {
        val src = source("FirstRunScreen.kt")
        assertTrue(
            src.contains("SyncSetupMessages.FIRST_RUN_ROW_LABEL"),
            "FirstRunScreen must render the first-run help row label",
        )
    }

    @Test
    fun `settings screen navigates to the sync setup screen`() {
        val src = source("SettingsScreen.kt")
        assertTrue(
            src.contains("navigateTo(::SyncSetupScreen)"),
            "SettingsScreen must navigate to SyncSetupScreen",
        )
    }

    @Test
    fun `settings screen renders the help row label`() {
        val src = source("SettingsScreen.kt")
        assertTrue(
            src.contains("SyncSetupMessages.SETTINGS_ROW_LABEL"),
            "SettingsScreen must render the settings help row label",
        )
    }

    @Test
    fun `the help row is not gated behind a debug build`() {
        // The dev "Render gallery" row is DEBUG-only; this one must NOT be — a
        // release-build user is exactly who needs it.
        val src = source("SettingsScreen.kt")
        val helpIndex = src.indexOf("SyncSetupMessages.SETTINGS_ROW_LABEL")
        val debugIndex = src.indexOf("BuildConfig.DEBUG")
        assertTrue(helpIndex > 0, "settings help row must exist")
        assertTrue(
            debugIndex < 0 || helpIndex < debugIndex,
            "the sync setup help row must sit outside/before the DEBUG-only block",
        )
    }

    @Test
    fun `the sync setup screen is scrollable and has a back button`() {
        val src = source("SyncSetupScreen.kt")
        assertTrue(src.contains("LightScrollView"), "the help screen must scroll")
        assertTrue(src.contains("LightIcons.BACK"), "the help screen needs a back button")
    }

    @Test
    fun `the sync setup screen makes no network calls`() {
        val src = source("SyncSetupScreen.kt")
        // A static help screen: no engine, no bridge, no coroutine fetch.
        listOf("EngineApi", "SyncController", "RecallEngine", "HttpURLConnection").forEach {
            assertTrue(!src.contains(it), "the help screen must stay offline and static (found $it)")
        }
    }
}
