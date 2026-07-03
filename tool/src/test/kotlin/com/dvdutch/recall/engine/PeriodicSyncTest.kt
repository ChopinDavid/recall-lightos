package com.dvdutch.recall.engine

import com.thelightphone.sdk.LightJobResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for the periodic-sync job's result mapping ([PeriodicSync.runOnce]). The pure
 * cases (null controller, latched needsAttention) need no network; the outcome-driven
 * cases (clean sync → Success, transient failure → Retry) use the real rslib client so
 * the mapping is validated against genuine [SyncController.sync] outcomes, not fakes.
 */
class PeriodicSyncTest {

    private companion object {
        const val ENDPOINT = "http://127.0.0.1:18080/"
        const val USER = "test"
        const val PW = "test123"
    }

    private lateinit var tmpDir: java.nio.file.Path

    private fun reachable(): Boolean =
        try {
            val c = (java.net.URI(ENDPOINT).toURL().openConnection() as java.net.HttpURLConnection)
            c.connectTimeout = 500; c.readTimeout = 500; c.requestMethod = "GET"; c.connect()
            c.responseCode; c.disconnect(); true
        } catch (_: Exception) {
            false
        }

    @BeforeTest
    fun setUp() {
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }
        tmpDir = Files.createTempDirectory("recall-periodic")
        runBlocking {
            withContext(EngineHolder.lane) {
                EngineHolder.openCollection(tmpDir.resolve("collection.anki2").toString())
            }
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.closeCollection() } }
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `runOnce with no controller is a no-op success`() {
        val result = runBlocking { PeriodicSync.runOnce(null) }
        assertTrue(result is LightJobResult.Success, "unconfigured periodic sync must be a no-op Success")
    }

    @Test
    fun `runOnce short-circuits to success when needsAttention is latched`() {
        val controller = SyncController(SyncConfig(ENDPOINT, USER, PW), EngineHolder).apply {
            setNeedsAttentionForTest(true)
        }
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        // A latched FULL_* is resolved out-of-band; a retry would only burn battery.
        assertTrue(result is LightJobResult.Success, "latched needsAttention must map to Success, not Retry")
    }

    @Test
    fun `runOnce maps a clean sync to success live`() {
        org.junit.Assume.assumeTrue("sync server not reachable", reachable())
        val controller = SyncController(SyncConfig(ENDPOINT, USER, PW), EngineHolder)
        runBlocking { controller.fullSync(upload = true) } // establish lineage
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        assertTrue(result is LightJobResult.Success, "a clean sync must map to Success")
    }

    @Test
    fun `runOnce maps a transient sync failure to retry live`() {
        org.junit.Assume.assumeTrue("sync server not reachable", reachable())
        // A bad password is a login/sync failure — non-fatal and transient from the
        // job's view — so runOnce must ask WorkManager to Retry with backoff.
        val controller = SyncController(SyncConfig(ENDPOINT, USER, "wrong-password"), EngineHolder)
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        assertTrue(result is LightJobResult.Retry, "a transient sync failure must map to Retry")
    }
}
