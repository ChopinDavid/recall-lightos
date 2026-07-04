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
        const val USER = ThrowawaySyncServer.USER
        const val PW = ThrowawaySyncServer.PW
    }

    private lateinit var tmpDir: java.nio.file.Path

    /**
     * A throwaway sync server for the live cases, or null when the pure cases run
     * without a server available (they need no network). The live cases pull it via
     * [requireServer], which SKIPs the test when no server could be spun up.
     */
    private var server: ThrowawaySyncServer? = null
    private fun requireServer(): ThrowawaySyncServer =
        server ?: ThrowawaySyncServer.start().also { server = it }

    /** Endpoint used only to build a [SyncConfig] for the pure (no-network) cases. */
    private val pureEndpoint = "http://127.0.0.1:18080/"

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
        server?.close()
    }

    @Test
    fun `runOnce with no controller is a no-op success`() {
        val result = runBlocking { PeriodicSync.runOnce(null) }
        assertTrue(result is LightJobResult.Success, "unconfigured periodic sync must be a no-op Success")
    }

    @Test
    fun `runOnce short-circuits to success when needsAttention is latched`() {
        val controller = SyncController(SyncConfig(pureEndpoint, USER, PW), EngineHolder).apply {
            setNeedsAttentionForTest(true)
        }
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        // A latched FULL_* is resolved out-of-band; a retry would only burn battery.
        assertTrue(result is LightJobResult.Success, "latched needsAttention must map to Success, not Retry")
    }

    @Test
    fun `runOnce maps a clean sync to success live`() {
        val controller = SyncController(requireServer().config(), EngineHolder)
        runBlocking { controller.fullSync(upload = true) } // establish lineage
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        assertTrue(result is LightJobResult.Success, "a clean sync must map to Success")
    }

    @Test
    fun `runOnce maps a transient sync failure to retry live`() {
        // A bad password is a login/sync failure — non-fatal and transient from the
        // job's view — so runOnce must ask WorkManager to Retry with backoff.
        val controller = SyncController(requireServer().config(password = "wrong-password"), EngineHolder)
        val result = runBlocking { PeriodicSync.runOnce(controller) }
        assertTrue(result is LightJobResult.Retry, "a transient sync failure must map to Retry")
    }
}
