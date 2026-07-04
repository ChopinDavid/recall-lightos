package com.dvdutch.recall.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Controller-level tests for the empty-server download guard, driven by a FAKE engine
 * (no native backend). These exercise the guard's orchestration — read local count,
 * back up, download, validate the result, roll back on trip/failure — via the injectable
 * [FullDownloadOps] seam, so the whole safety contract is unit-testable without a hub.
 *
 * The five cases mirror the incident's requirements:
 *  1. non-empty local + empty server  → GuardTripped, NO wipe (backup restored, no clear)
 *  2. user confirms (force=true)      → download proceeds, empty result kept
 *  3. empty local (first-run)         → no guard, downloads with no friction
 *  4. non-empty server                → no guard, downloads
 *  5. mid-download failure            → local restored, retriable Failed result
 */
class FullDownloadTest {

    /**
     * A fully in-memory fake of the destructive full-download primitives. It models a
     * "collection" as a single mutable card count plus a nullable backup slot, so we can
     * assert that a trip or a failure leaves the ORIGINAL count intact (never wiped).
     */
    private class FakeOps(
        initialLocalCount: Int,
        private val downloadedCount: Int,
        private val downloadThrows: Throwable? = null,
        private val restoreThrows: Throwable? = null,
    ) : FullDownloadOps {
        var liveCount: Int = initialLocalCount
        private var backup: Int? = null
        var backupTaken = false
        var restored = false
        var downloadAttempted = false

        override suspend fun localCardCount(): Int = liveCount

        override suspend fun backupCollection() {
            backup = liveCount
            backupTaken = true
        }

        override suspend fun download() {
            downloadAttempted = true
            downloadThrows?.let { throw it }
            liveCount = downloadedCount // the transfer replaces the whole collection
        }

        override suspend fun restoreCollection() {
            restoreThrows?.let { throw it } // a crash/kill mid-roll-back
            liveCount = backup ?: error("restore with no backup")
            restored = true
        }
    }

    @Test
    fun `non-empty local plus empty server trips the guard and does not wipe`() {
        val ops = FakeOps(initialLocalCount = 14_046, downloadedCount = 0)
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        val tripped = assertIs<FullDownloadResult.GuardTripped>(result)
        assertEquals(14_046, tripped.localCardCount, "the guard must report the real local count")
        assertEquals(14_046, ops.liveCount, "the populated phone must be intact after a trip")
        assertTrue(ops.restored, "a trip must restore the backed-up collection")
    }

    @Test
    fun `forcing past the guard proceeds with the empty download`() {
        val ops = FakeOps(initialLocalCount = 14_046, downloadedCount = 0)
        val result = runBlocking { FullDownloadFlow.run(ops, force = true) }

        assertIs<FullDownloadResult.Downloaded>(result)
        assertEquals(0, ops.liveCount, "a forced download keeps the empty server result")
        assertFalse(ops.restored, "a forced download must NOT roll back")
    }

    @Test
    fun `first-run empty local downloads with no guard`() {
        val ops = FakeOps(initialLocalCount = 0, downloadedCount = 0)
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        assertIs<FullDownloadResult.Downloaded>(result)
        assertFalse(ops.backupTaken, "first-run has nothing to protect — no backup needed")
        assertFalse(ops.restored, "first-run never rolls back")
    }

    @Test
    fun `a non-empty server downloads without tripping`() {
        val ops = FakeOps(initialLocalCount = 14_046, downloadedCount = 11_244)
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        assertIs<FullDownloadResult.Downloaded>(result)
        assertEquals(11_244, ops.liveCount, "the server's collection is kept")
        assertFalse(ops.restored, "a legitimate download must not roll back")
    }

    @Test
    fun `a mid-download failure restores the local collection and is retriable`() {
        val ops = FakeOps(
            initialLocalCount = 14_046,
            downloadedCount = 0,
            downloadThrows = RuntimeException("connection reset mid-transfer"),
        )
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        val failed = assertIs<FullDownloadResult.Failed>(result)
        assertTrue("connection reset" in failed.reason, "the failure reason must surface: ${failed.reason}")
        assertEquals(14_046, ops.liveCount, "a mid-download failure must leave the old collection intact")
        assertTrue(ops.restored, "a mid-download failure must restore the backup")
    }

    // --- Backup lifecycle (finding #3): a restore that THROWS must not crash the app ---

    // A restore that throws on the mid-download-failure path must NOT propagate: the
    // download already failed, and now roll-back was interrupted too — but the guard's
    // open-time recovery ([GuardBackupRecovery]) restores from the still-present backup on
    // the next launch, so the flow surfaces the retriable Failed error state, never crashes.
    @Test
    fun `a restore that throws on a failed download yields a retriable failure, not a crash`() {
        val ops = FakeOps(
            initialLocalCount = 14_046,
            downloadedCount = 0,
            downloadThrows = RuntimeException("connection reset mid-transfer"),
            restoreThrows = RuntimeException("disk full during roll-back"),
        )
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        assertIs<FullDownloadResult.Failed>(result) // surfaced, not thrown
        assertFalse(ops.restored, "restore threw — the backup is LEFT for open-time recovery")
    }

    // A restore that throws on the guard-tripped path (empty server, populated phone) must
    // likewise surface a retriable Failed rather than propagate: the backup is left intact
    // and recovered on the next open, and the UI re-decides.
    @Test
    fun `a restore that throws on a guard trip yields a retriable failure, not a crash`() {
        val ops = FakeOps(
            initialLocalCount = 14_046,
            downloadedCount = 0,
            restoreThrows = RuntimeException("process killed mid-roll-back"),
        )
        val result = runBlocking { FullDownloadFlow.run(ops, force = false) }

        assertIs<FullDownloadResult.Failed>(result) // NOT GuardTripped, NOT a thrown crash
        assertFalse(ops.restored, "restore threw — the backup is LEFT for open-time recovery")
    }
}
