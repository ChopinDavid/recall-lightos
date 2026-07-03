package com.dvdutch.recall.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live-server tests for [SyncController]. These drive the REAL rslib sync stack
 * against a REAL local `anki-sync-server`, exactly as Task 5 acceptance will on
 * device — no fakes for network behaviour.
 *
 * The server is expected at [ENDPOINT] with account [USER]/[PW]. Launch it with:
 * ```
 * SYNC_USER1=test:test123 SYNC_BASE=<tmp> SYNC_HOST=127.0.0.1 SYNC_PORT=18080 \
 *   python -m anki.syncserver
 * ```
 * When the server is unreachable these tests are skipped (assumeTrue), so the suite
 * stays green in CI without a hub; the on-device sync path is validated in Task 5.
 *
 * Bootstrap parity with the bridge's `_operator_bootstrap` (test_study_sync.py): a
 * fresh empty server has no collection, so the first `syncCollection` returns
 * FULL_* required. We seed the server via a one-time full UPLOAD from a throwaway
 * collection before asserting normal-sync behaviour.
 */
class SyncControllerTest {

    private companion object {
        const val ENDPOINT = "http://127.0.0.1:18080/"
        const val USER = "test"
        const val PW = "test123"
    }

    private lateinit var tmpDir: java.nio.file.Path

    private fun serverReachable(): Boolean =
        try {
            val c = (java.net.URI(ENDPOINT).toURL().openConnection() as java.net.HttpURLConnection)
            c.connectTimeout = 500
            c.readTimeout = 500
            c.requestMethod = "GET"
            c.connect()
            c.responseCode // any HTTP response means it's up
            c.disconnect()
            true
        } catch (_: Exception) {
            false
        }

    @BeforeTest
    fun setUp() {
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }
        tmpDir = Files.createTempDirectory("recall-sync")
        val colPath = tmpDir.resolve("collection.anki2").toString()
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.openCollection(colPath) } }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.closeCollection() } }
        tmpDir.toFile().deleteRecursively()
    }

    private fun config() = SyncConfig(endpoint = ENDPOINT, username = USER, password = PW)

    /** Records every durable needs-attention write the controller makes. */
    private class RecordingPersist {
        val writes = mutableListOf<Boolean>()
        val callback: suspend (Boolean) -> Unit = { writes.add(it) }
        val last: Boolean? get() = writes.lastOrNull()
    }

    @Test
    fun `FULL_* latch persists needs-attention true via the callback`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        // Establish a known server state by full-uploading THIS collection.
        runBlocking { SyncController(config(), EngineHolder).fullSync(upload = true) }

        // A SECOND, never-synced collection diverges → FULL_* on a normal sync.
        val otherDir = Files.createTempDirectory("recall-sync-persist")
        val otherCol = otherDir.resolve("collection.anki2").toString()
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.openCollection(otherCol) } }
        try {
            val persist = RecordingPersist()
            val controller = SyncController(config(), EngineHolder, persistNeedsAttention = persist.callback)
            val info = runBlocking { controller.sync(media = false) }
            assertFalse(info.synced, "a divergent collection must not clean-sync")
            assertTrue(controller.needsAttention.value, "FULL_* must latch the in-memory flow too")
            assertEquals(true, persist.last, "FULL_* must durably persist needs-attention=true")
        } finally {
            runBlocking {
                withContext(EngineHolder.lane) {
                    EngineHolder.openCollection(tmpDir.resolve("collection.anki2").toString())
                }
            }
            otherDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fullSync success persists needs-attention false via the callback`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        val persist = RecordingPersist()
        val controller = SyncController(config(), EngineHolder, persistNeedsAttention = persist.callback)
        runBlocking { controller.fullSync(upload = true) }
        assertFalse(controller.needsAttention.value, "fullSync clears the in-memory latch")
        assertEquals(false, persist.last, "a successful fullSync must durably clear needs-attention")
    }

    @Test
    fun `login caches auth and a repeat login reuses the cached SyncAuth`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        val controller = SyncController(config(), EngineHolder)
        val first = runBlocking { controller.login() }
        assertNotNull(first, "login against the live server must return a SyncAuth")
        assertTrue(first.hkey.isNotBlank(), "SyncAuth must carry an hkey")
        // Second login returns the SAME cached instance (no re-login round-trip).
        val second = runBlocking { controller.login() }
        assertTrue(first === second, "login() must return the cached auth on the second call")
    }

    @Test
    fun `fullSync upload establishes lineage so the next normal sync is clean and stamps lastSync`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        // Order-independent by construction: we full-UPLOAD our own collection first,
        // so the server's collection now shares our lineage regardless of what any
        // other test left behind (a full upload replaces server state). This is the
        // bridge's `_operator_bootstrap` (test_study_sync.py) semantics.
        val controller = SyncController(config(), EngineHolder)
        assertNull(controller.lastSync.value, "lastSync starts null")
        runBlocking { controller.fullSync(upload = true) }

        // The engine must still be usable after fullSync: the collection is reopened
        // internally by the backend (AnkiDroid reopen(afterFullSync=true) semantics),
        // so a deck-tree read must succeed WITHOUT any explicit re-open.
        val childCount = runBlocking {
            withContext(EngineHolder.lane) { EngineHolder.backend().deckTree(0).childrenCount }
        }
        assertTrue(childCount >= 1, "collection must be usable after fullSync (backend reopened it)")

        // A normal sync right after a full upload is a clean success (server matches us).
        val info = runBlocking { controller.sync(media = false) }
        assertTrue(info.synced, "normal sync after full upload must be clean, got: ${info.detail}")
        assertFalse(controller.needsAttention.value, "a clean sync must not raise needsAttention")
        assertNotNull(controller.lastSync.value, "a successful sync must stamp lastSync")
    }

    @Test
    fun `a divergent never-synced collection requires full sync and latches needsAttention live`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        // Establish a known server state by full-uploading THIS collection.
        runBlocking { SyncController(config(), EngineHolder).fullSync(upload = true) }

        // Open a SECOND, independently-created collection that has NEVER synced with
        // this server. It shares no sync lineage, so the real rslib client reports a
        // FULL_* requirement on a normal sync — not a clean merge.
        val otherDir = Files.createTempDirectory("recall-sync-divergent")
        val otherCol = otherDir.resolve("collection.anki2").toString()
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.openCollection(otherCol) } }
        try {
            val controller = SyncController(config(), EngineHolder)
            val info = runBlocking { controller.sync(media = false) }
            assertFalse(info.synced, "a divergent collection must not clean-sync")
            assertTrue(
                "needs attention" in info.detail,
                "FULL_* must surface as needs-attention detail, got: ${info.detail}",
            )
            assertTrue(controller.needsAttention.value, "FULL_* must latch needsAttention")
            assertNull(controller.lastSync.value, "a FULL_*-blocked sync must not stamp lastSync")

            // Once latched, a further sync short-circuits without a server round-trip.
            val again = runBlocking { controller.sync(media = false) }
            assertFalse(again.synced)
            assertTrue("needs attention" in again.detail)
        } finally {
            runBlocking {
                withContext(EngineHolder.lane) {
                    EngineHolder.openCollection(tmpDir.resolve("collection.anki2").toString())
                }
            }
            otherDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `login failure drops the cached auth so the next attempt re-logs in`() {
        org.junit.Assume.assumeTrue("sync server not reachable", serverReachable())
        val badConfig = SyncConfig(endpoint = ENDPOINT, username = USER, password = "wrong-password")
        val controller = SyncController(badConfig, EngineHolder)
        val info = runBlocking { controller.sync(media = false) }
        assertFalse(info.synced, "a bad-password sync must fail non-fatally")
        assertTrue(info.detail.startsWith("sync failed"), "detail must be a sync-failed message: ${info.detail}")
        // Auth was dropped, not cached: the controller has no cached auth to reuse.
        assertNull(controller.cachedAuthForTest(), "failed login must drop the cached auth")
    }

    @Test
    fun `sync short-circuits when needsAttention is already set`() {
        // Pure/no-network: once needsAttention latches, sync must not touch the server.
        val controller = SyncController(config(), EngineHolder)
        controller.setNeedsAttentionForTest(true)
        val info = runBlocking { controller.sync(media = false) }
        assertFalse(info.synced)
        assertTrue("needs attention" in info.detail, "detail must mention needs attention: ${info.detail}")
    }
}
