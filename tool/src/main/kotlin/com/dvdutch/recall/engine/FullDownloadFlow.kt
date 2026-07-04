package com.dvdutch.recall.engine

/**
 * The four destructive primitives a guarded full download orchestrates. Extracted as a
 * seam so the guard's safety contract ([FullDownloadFlow.run]) is unit-testable with a
 * fully in-memory fake — no native backend, no sync hub. [SyncController] provides the
 * real implementation over rslib + the on-disk collection file.
 */
interface FullDownloadOps {
    /** Cards in the currently-open collection (0 on first-run / empty). */
    suspend fun localCardCount(): Int

    /** Copy the current collection file aside so a trip/failure can roll back to it. */
    suspend fun backupCollection()

    /** Run the one-way full download, replacing the whole local collection. */
    suspend fun download()

    /** Restore the backed-up collection file and reopen it — the pre-download state. */
    suspend fun restoreCollection()
}

/** The outcome of a guarded full download. */
sealed interface FullDownloadResult {
    /** The download completed and its result is kept (server was non-empty, or forced). */
    data object Downloaded : FullDownloadResult

    /**
     * The guard tripped: a populated phone ([localCardCount] cards) was about to be
     * replaced by an EMPTY server collection. The download was rolled back — the phone is
     * intact — and the UI must ask for explicit confirmation with these concrete numbers.
     */
    data class GuardTripped(val localCardCount: Int) : FullDownloadResult

    /** The download failed mid-transfer; the local collection was restored. Retriable. */
    data class Failed(val reason: String) : FullDownloadResult
}

/**
 * Orchestrates a guarded full download around the [FullDownloadOps] primitives.
 *
 * Because rsdroid exposes no cheap pre-check for the server's collection size, the guard
 * validates the RESULT and rolls back rather than probing first (see [FullDownloadGuard]):
 *
 *  1. Read the local card count. If it is empty (first-run), just download — nothing to
 *     lose, no backup, no guard.
 *  2. Otherwise back up the collection file, then download.
 *  3. If the download threw, restore the backup → [FullDownloadResult.Failed] (retriable);
 *     the old collection is left intact, never a corrupt half-state.
 *  4. If [FullDownloadGuard.tripsOn] the (local, downloaded) counts and the caller did NOT
 *     [force], restore the backup → [FullDownloadResult.GuardTripped]; the phone keeps its
 *     cards and the UI confirms with concrete numbers before any wipe.
 *  5. Otherwise (server non-empty, or forced past the guard) keep the download →
 *     [FullDownloadResult.Downloaded].
 */
object FullDownloadFlow {

    suspend fun run(ops: FullDownloadOps, force: Boolean): FullDownloadResult {
        val localCount = ops.localCardCount()

        // First-run / empty local: nothing to protect, no guard, no backup.
        if (localCount == 0) {
            return try {
                ops.download()
                FullDownloadResult.Downloaded
            } catch (t: Throwable) {
                FullDownloadResult.Failed(t.message ?: t.toString())
            }
        }

        ops.backupCollection()
        try {
            ops.download()
        } catch (t: Throwable) {
            ops.restoreCollection()
            return FullDownloadResult.Failed(t.message ?: t.toString())
        }

        val downloadedCount = ops.localCardCount()
        if (!force && FullDownloadGuard.tripsOn(localCount, downloadedCount)) {
            ops.restoreCollection()
            return FullDownloadResult.GuardTripped(localCount)
        }
        return FullDownloadResult.Downloaded
    }
}
