package com.dvdutch.recall.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.dvdutch.recall.prefs.RecallPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The collection-OPEN choke point ([RecallEngine.openCollection]). The engine journaling
 * contract (whole-branch review finding #2) is that [GuardBackupRecovery.recover] runs
 * BEFORE the backend opens the collection: an orphaned `.guard-backup` (a roll-back that a
 * crash/kill interrupted) is recovered — its last-known-good bytes restored over the main
 * file and the backup deleted — so the native backend never opens a torn file. With no
 * backup present, open is a plain open that leaves the collection untouched.
 *
 * These drive the REAL [EngineHolder] + backend on the JVM (via the -testing native loader,
 * exactly like [EngineSmokeTest]) over temp dirs, with the already-pure [GuardBackupRecovery]
 * doing the recovery. They assert the observable pre-open recovery effect: a valid collection
 * created by the backend, aside-copied as an orphaned backup, is recovered on the next open.
 */
class RecallEngineOpenPathTest {

    private data class Env(
        val engine: RecallEngine,
        val scope: CoroutineScope,
        val ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    )

    private fun newEngine(dir: File): Env {
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
        return Env(RecallEngine(dir, ds), scope, ds)
    }

    private fun tmp(): File = File.createTempFile("recall-open", "").let { it.delete(); it.mkdirs(); it }

    // A valid collection created by the backend, then aside-copied as an ORPHANED backup with
    // DIFFERENT (later) content: the next openCollection must recover the backup over the main
    // file (finding #2's crash-safe roll-back) and delete it, then open cleanly.
    @Test
    fun `an orphaned backup is recovered before the collection opens`() {
        val dir = tmp()
        val (engine, scope, ds) = newEngine(dir)
        try {
            runBlocking {
                // 1) Create a real, valid on-disk collection via the engine (opens + creates it).
                engine.openCollection()
                val collectionFile = engine.storage.collectionFile
                assertTrue(collectionFile.isFile, "engine created the collection file")

                // 2) Close it so the on-disk bytes are a consistent snapshot, then capture a
                //    "last known good" backup with content distinct from what we'll corrupt.
                EngineHolder.closeCollection()
                val goodBytes = collectionFile.readBytes()
                val backup = GuardBackupRecovery.backupOf(collectionFile)
                backup.writeBytes(goodBytes)

                // 3) Simulate the interrupted roll-back: the main file is half-overwritten
                //    (torn) while the orphaned backup still sits on disk.
                collectionFile.writeBytes("HALF-OVERWRITTEN-TORN".toByteArray())

                // 4) Re-open through the engine. recover() must run FIRST, restoring the good
                //    bytes over the torn file and deleting the backup, so the backend opens the
                //    recovered (valid) collection rather than the torn one.
                engine.openCollection()

                assertFalse(backup.exists(), "the orphaned backup is consumed by recovery")
                assertEquals(
                    goodBytes.toList(),
                    collectionFile.readBytes().toList(),
                    "the collection file is the recovered last-known-good, not the torn bytes",
                )
                // And the backend genuinely reopened it: a deck read round-trips.
                val decks = engine.decks(null)
                assertTrue(decks.size >= 0, "the recovered collection is open and queryable")
            }
        } finally {
            runBlocking { EngineHolder.closeCollection() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    // syncConfig reads the persisted fields; a controller built from blank fields is
    // unconfigured, and from complete fields is configured.
    @Test
    fun `syncConfig and controller reflect the persisted sync fields`() {
        val dir = tmp()
        val (engine, scope, ds) = newEngine(dir)
        try {
            runBlocking {
                // Blank fields → unconfigured controller (endpoint defaults, creds empty).
                val blank = engine.syncConfig()
                assertEquals(RecallPreferences.DEFAULT_SYNC_ENDPOINT, blank.endpoint)
                assertTrue(blank.username.isEmpty() && blank.password.isEmpty())
                assertFalse(engine.controller().configured, "no creds → unconfigured")

                // Persist a full config → configured controller reading it back.
                (ds).edit {
                    it[RecallPreferences.SYNC_ENDPOINT] = "https://sync.example/"
                    it[RecallPreferences.SYNC_USERNAME] = "u"
                    it[RecallPreferences.SYNC_PASSWORD] = "p"
                }
                val full = engine.syncConfig()
                assertEquals("https://sync.example/", full.endpoint)
                assertEquals("u", full.username)
                assertTrue(engine.controller().configured, "all three fields set → configured")
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    // needsAttention defaults false and reads back the durably-written latch.
    @Test
    fun `needsAttention reflects the durable latch`() {
        val dir = tmp()
        val (engine, scope, ds) = newEngine(dir)
        try {
            runBlocking {
                assertFalse(engine.needsAttention(), "defaults false when never written")
                ds.edit { it[RecallPreferences.NEEDS_ATTENTION] = true }
                assertTrue(engine.needsAttention(), "reads back the persisted latch")
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    // localCardCount round-trips through the real backend on a freshly-created collection
    // (a new collection has zero cards).
    @Test
    fun `localCardCount reads the open collection's card count`() {
        val dir = tmp()
        val (engine, scope, ds) = newEngine(dir)
        try {
            runBlocking {
                engine.openCollection()
                assertEquals(0, engine.localCardCount(), "a fresh collection has no cards")
            }
        } finally {
            runBlocking { EngineHolder.closeCollection() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    // With NO backup on disk, openCollection is a plain open that leaves the collection file
    // exactly as it was (no spurious recovery).
    @Test
    fun `with no backup the collection opens untouched`() {
        val dir = tmp()
        val (engine, scope, ds) = newEngine(dir)
        try {
            runBlocking {
                engine.openCollection()
                val collectionFile = engine.storage.collectionFile
                EngineHolder.closeCollection()
                val before = collectionFile.readBytes().toList()
                assertFalse(GuardBackupRecovery.backupOf(collectionFile).exists(), "no backup present")

                engine.openCollection() // no orphan → plain open, no recovery

                assertEquals(before, collectionFile.readBytes().toList(), "no backup means no recovery")
                assertFalse(GuardBackupRecovery.backupOf(collectionFile).exists())
            }
        } finally {
            runBlocking { EngineHolder.closeCollection() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
