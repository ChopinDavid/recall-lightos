package com.dvdutch.recall.prefs

import com.dvdutch.recall.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync-endpoint default is BUILD-TYPE scoped (a publishing gate): the shipping
 * (RELEASE) build must ship NO dev server, while DEBUG keeps the host-local emulator
 * hub prefilled so the emulator workflow stays one-tap. The value is threaded through
 * `BuildConfig.DEV_DEFAULT_ENDPOINT`, set per build type in tool/build.gradle.kts.
 *
 * This suite runs under `testDebugUnitTest`, so it compiles against the DEBUG
 * BuildConfig and asserts the debug default is the dev hub. The empty RELEASE default
 * is enforced by the build script (`buildConfigField("String", "DEV_DEFAULT_ENDPOINT",
 * "\"\"")` in the release build type) and verified end-to-end on the emulator: a
 * release first-run shows an EMPTY endpoint.
 */
class EndpointDefaultTest {

    @Test
    fun `debug build default is the host-local dev hub`() {
        assertEquals("http://10.0.2.2:18080/", RecallPreferences.DEFAULT_SYNC_ENDPOINT)
    }

    @Test
    fun `RecallPreferences default mirrors BuildConfig field`() {
        assertEquals(BuildConfig.DEV_DEFAULT_ENDPOINT, RecallPreferences.DEFAULT_SYNC_ENDPOINT)
    }

    @Test
    fun `the debug default is a valid endpoint`() {
        assertTrue(TextSanitizer.isValidEndpoint(RecallPreferences.DEFAULT_SYNC_ENDPOINT))
    }
}
