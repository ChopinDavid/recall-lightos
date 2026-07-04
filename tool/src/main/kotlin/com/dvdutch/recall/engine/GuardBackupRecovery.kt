package com.dvdutch.recall.engine

import java.io.File
import java.io.FileOutputStream

/**
 * Journaling-style crash recovery for the empty-server download guard's rollback
 * (whole-branch review finding #2).
 *
 * The guard protects a populated phone from being wiped by an EMPTY server: before a
 * full download it copies the live collection aside as `<name>.guard-backup`, and on a
 * trip / mid-transfer failure it rolls back by copying that backup back OVER the main
 * `collection.anki2`. The window this closes: a crash / process-kill / OOM DURING that
 * roll-back copy leaves the main file half-overwritten with the `.guard-backup` still on
 * disk — corrupt, and previously undetected on next launch.
 *
 * [recover] is invoked at the single collection-OPEN choke point ([RecallEngine.openCollection])
 * BEFORE the backend opens the file. Its contract:
 *   - a `.guard-backup` on disk means the last restore was interrupted → the backup, by
 *     definition the last known-good pre-download state, is recovered over the main file;
 *   - the recovery copy is itself crash-safe: backup → recovery temp → fsync → atomic
 *     rename over the main file → delete backup. `File.renameTo` within one directory is
 *     atomic on ext4/f2fs (Android's filesystems), so a crash mid-recovery leaves either
 *     the old main file (rename not yet done) or the new one (rename done) — never a torn
 *     write — and a re-run finishes the job from the still-present backup;
 *   - a stale recovery temp with no backup (recovery had all but finished) is swept, the
 *     main file left authoritative;
 *   - no backup and no temp → a pure no-op.
 *
 * Pure [File] I/O — no native backend, no Android runtime — so it is unit-tested directly.
 */
object GuardBackupRecovery {

    /** Suffix of the guard's pre-download safety copy, beside the collection file. */
    const val BACKUP_SUFFIX = ".guard-backup"

    /** Suffix of the in-flight recovery temp (rename target), beside the collection file. */
    private const val RECOVERING_SUFFIX = ".guard-recovering"

    /** The `.guard-backup` companion of [collectionFile]. */
    fun backupOf(collectionFile: File): File =
        File(collectionFile.parentFile, collectionFile.name + BACKUP_SUFFIX)

    private fun recoveryTempOf(collectionFile: File): File =
        File(collectionFile.parentFile, collectionFile.name + RECOVERING_SUFFIX)

    /**
     * Recovers an interrupted guard roll-back for [collectionFile], if one is detected.
     * Safe to call unconditionally before opening the collection: a no-op when no
     * `.guard-backup`/recovery temp is present.
     */
    fun recover(collectionFile: File) {
        val backup = backupOf(collectionFile)
        val temp = recoveryTempOf(collectionFile)

        if (!backup.isFile) {
            // No orphaned backup: the last restore (if any) completed. Sweep any stale
            // recovery temp from a crash that happened after the backup was removed —
            // the main file is authoritative — and we are done.
            if (temp.exists()) temp.delete()
            return
        }

        // An orphaned backup: the previous restore was interrupted. Recover from it with
        // a crash-safe copy → fsync → atomic rename, so a crash DURING recovery is also
        // survivable (either the pre-rename main file or the fully-recovered one survives,
        // and the still-present backup lets a re-run finish).
        if (temp.exists()) temp.delete() // discard any partial temp from a prior crashed run
        copyWithFsync(backup, temp)
        if (!temp.renameTo(collectionFile)) {
            // Same-directory rename failed (shouldn't on ext4/f2fs); fall back to a plain
            // overwrite copy so recovery still completes, then clean up the temp.
            backup.copyTo(collectionFile, overwrite = true)
            temp.delete()
        }
        backup.delete()
    }

    /** Copies [from] to [to] and fsyncs the bytes to disk before returning. */
    private fun copyWithFsync(from: File, to: File) {
        from.inputStream().use { input ->
            FileOutputStream(to).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync() // durable before the atomic rename
            }
        }
    }
}
