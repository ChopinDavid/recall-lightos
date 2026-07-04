package com.dvdutch.recall.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure decision tests for [FullDownloadGuard.tripsOn]. The guard exists because a full
 * download replaces the ENTIRE local collection with the server's copy: three times a
 * client full-downloaded an EMPTY server state and silently wiped a populated phone. The
 * decision is deliberately narrow — it only trips when a SUBSTANTIAL local collection is
 * about to be replaced by an EMPTY/trivial one — so a legitimately small server never
 * blocks a legitimate download, and first-run (empty local) never trips.
 */
class FullDownloadGuardTest {

    @Test
    fun `trips when local is substantial and the download is empty`() {
        assertTrue(FullDownloadGuard.tripsOn(localCount = 14_046, downloadedCount = 0))
    }

    @Test
    fun `does not trip on first run when local is empty`() {
        assertFalse(FullDownloadGuard.tripsOn(localCount = 0, downloadedCount = 0))
    }

    @Test
    fun `does not trip when the server is non-empty`() {
        assertFalse(FullDownloadGuard.tripsOn(localCount = 14_046, downloadedCount = 11_244))
    }

    @Test
    fun `does not trip when a single local card is replaced by a non-empty server`() {
        assertFalse(FullDownloadGuard.tripsOn(localCount = 1, downloadedCount = 1))
    }

    @Test
    fun `trips when even a single local card would be wiped to empty`() {
        // The incident's failure mode is general: ANY populated phone replaced by empty
        // is the silent wipe we must catch, not just the 14k case.
        assertTrue(FullDownloadGuard.tripsOn(localCount = 1, downloadedCount = 0))
    }
}
