package com.dvdutch.recall.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash-recovery for an orphaned `.guard-backup` (whole-branch review finding #2).
 *
 * The empty-server download guard rolls back by copying `collection.anki2.guard-backup`
 * OVER `collection.anki2`. A crash / process kill / OOM DURING that copy leaves the main
 * file half-overwritten with the backup still on disk — and nothing detected it. These
 * tests pin the journaling recovery [GuardBackupRecovery.recover] runs at collection-open
 * time: an orphaned backup means the previous restore was interrupted, so the backup (the
 * last known-good pre-download state) is recovered over the main file and then deleted.
 *
 * Pure [File] arithmetic against real temp dirs — no native backend, no Android runtime.
 */
class GuardBackupRecoveryTest {

    private fun tmpDir(): File =
        File.createTempFile("guard-recovery", "").let { it.delete(); it.mkdirs(); it }

    private fun collectionIn(dir: File): File = File(dir, "collection.anki2")
    private fun backupOf(file: File): File = File(file.parentFile, file.name + ".guard-backup")
    private fun recoveryTempOf(file: File): File =
        File(file.parentFile, file.name + ".guard-recovering")

    @Test
    fun `an orphaned backup recovers its content over the main file and is deleted`() {
        val dir = tmpDir()
        val collection = collectionIn(dir)
        collection.writeBytes("HALF-OVERWRITTEN-CORRUPT".toByteArray())
        val backup = backupOf(collection)
        backup.writeBytes("LAST-KNOWN-GOOD".toByteArray())

        GuardBackupRecovery.recover(collection)

        assertEquals("LAST-KNOWN-GOOD", collection.readText(), "main file must be the recovered backup")
        assertFalse(backup.exists(), "the backup must be deleted once recovered")
        dir.deleteRecursively()
    }

    @Test
    fun `no backup leaves the collection untouched`() {
        val dir = tmpDir()
        val collection = collectionIn(dir)
        collection.writeBytes("LIVE".toByteArray())

        GuardBackupRecovery.recover(collection)

        assertEquals("LIVE", collection.readText(), "no orphan means no recovery")
        assertFalse(backupOf(collection).exists())
        dir.deleteRecursively()
    }

    @Test
    fun `no collection dir at all is a safe no-op`() {
        val dir = tmpDir()
        val collection = collectionIn(File(dir, "not-created-yet"))

        GuardBackupRecovery.recover(collection) // must not throw

        assertFalse(collection.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `an interrupted recovery leaves a temp file that a re-run completes`() {
        // A crash DURING recovery itself: the backup was copied to the recovery temp
        // name but the atomic rename had not yet happened, so both the temp and the
        // backup are on disk. A re-run must still recover from the backup and clean up.
        val dir = tmpDir()
        val collection = collectionIn(dir)
        collection.writeBytes("HALF-OVERWRITTEN".toByteArray())
        val backup = backupOf(collection)
        backup.writeBytes("LAST-KNOWN-GOOD".toByteArray())
        // Simulate the interrupted recovery temp file from a prior crashed run.
        recoveryTempOf(collection).writeBytes("STALE-TEMP".toByteArray())

        GuardBackupRecovery.recover(collection)

        assertEquals("LAST-KNOWN-GOOD", collection.readText())
        assertFalse(backup.exists(), "backup cleaned up")
        assertFalse(recoveryTempOf(collection).exists(), "stale recovery temp cleaned up")
        dir.deleteRecursively()
    }

    @Test
    fun `a stale recovery temp with no backup is cleaned up without touching the collection`() {
        // If only the temp survives (backup already deleted, i.e. recovery had all but
        // finished), the main file is authoritative — just sweep the temp.
        val dir = tmpDir()
        val collection = collectionIn(dir)
        collection.writeBytes("LIVE".toByteArray())
        recoveryTempOf(collection).writeBytes("STALE-TEMP".toByteArray())

        GuardBackupRecovery.recover(collection)

        assertEquals("LIVE", collection.readText(), "no backup: main file is authoritative")
        assertFalse(recoveryTempOf(collection).exists(), "stale recovery temp swept")
        dir.deleteRecursively()
    }
}
