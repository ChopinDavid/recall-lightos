package com.dvdutch.recall.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Sync against a server that isn't there. Unlike [SyncControllerTest] this needs no
 * throwaway sync server, so it runs everywhere (CI included): it pins that the real
 * backend raises its network error for an unreachable endpoint, which is what lets
 * [SyncFailure] explain the failure instead of showing rslib's raw text.
 */
class SyncControllerNetworkTest {

    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }
        tmpDir = Files.createTempDirectory("recall-sync-net")
        val colPath = tmpDir.resolve("collection.anki2").toString()
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.openCollection(colPath) } }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.closeCollection() } }
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `an unreachable server is explained, not shown as a raw error`() {
        // Port 1 on loopback refuses immediately; nothing listens there.
        val config = SyncConfig("http://127.0.0.1:1/", "user", "pass")
        val controller = SyncController(config, EngineHolder, networks = { listOf(LocalNetwork(0x0A00020F, 24)) })
        val info = runBlocking { controller.sync(media = false) }
        assertFalse(info.synced)
        assertEquals(SyncFailure.ServerUnreachable("127.0.0.1:1").syncDetail, info.detail)
    }
}
