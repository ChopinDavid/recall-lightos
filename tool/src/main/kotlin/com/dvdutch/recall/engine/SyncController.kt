package com.dvdutch.recall.engine

import anki.sync.FullUploadOrDownloadRequest
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse
import anki.sync.SyncLoginRequest
import com.dvdutch.recall.api.SyncInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where to sync and as whom. Passed in by the caller (this task does NOT read prefs —
 * Task 4 owns the prefs rewrite). A controller is only [SyncController.configured] when
 * all three fields are non-blank.
 */
data class SyncConfig(
    val endpoint: String,
    val username: String,
    val password: String,
)

/**
 * The on-device sync layer over rslib's own client. It is the Kotlin twin of the
 * bridge's Python `SyncManager` (`anki_bridge.sync`) — same non-fatal contract, same
 * `needsAttention`-on-FULL_* gate, same drop-auth-on-failure — so a study session syncs
 * identically whether it runs on the phone or via the bridge.
 *
 * ## Semantic reference: `anki_bridge.sync.SyncManager`
 *   - `sync()` is **non-fatal**: any login/sync exception → `SyncInfo(false, "sync failed: …")`
 *     and studying proceeds on the local collection (spec: sync failures never block study).
 *   - A FULL_* requirement is the ONE exception: it latches [needsAttention] and is resolved
 *     only out-of-band (a full sync via [fullSync], or the operator on the bridge). While
 *     latched, [sync] short-circuits without touching the server.
 *   - [SyncAuth] is cached in memory after a successful login and **dropped on any failure**
 *     so the next attempt performs a fresh login.
 *
 * ## Concurrency
 * Every engine touch is confined to [EngineHolder.lane] (the serial native lane) exactly
 * like [LocalEngineApi]; the raw `Backend` handle is never used off-lane. The state flows
 * ([needsAttention], [lastSync]) are plain [MutableStateFlow]s mutated only from lane-confined
 * code, so their reads are consistent with the last completed sync.
 */
open class SyncController(
    private val config: SyncConfig,
    private val holder: EngineHolder,
    /**
     * Durable sink for the needs-attention latch. [needsAttention] is in-memory and dies
     * with each controller instance a ViewModel constructs, but a FULL_* divergence persists
     * across sessions and process restarts — so the durable source of truth is a persisted
     * pref. [RecallEngine.controller] wires this to DataStore; tests pass a fake. The default
     * no-op keeps the seam optional for callers that don't need durability.
     */
    private val persistNeedsAttention: suspend (Boolean) -> Unit = {},
    /**
     * The on-disk collection file, for the empty-server download guard ([fullDownload]):
     * a guarded download backs this file up before the transfer so a wipe-to-empty or a
     * mid-transfer failure can be rolled back. Null disables the guard (the caller has no
     * file to protect — e.g. a controller built only for upload/normal sync), in which
     * case [fullDownload] falls back to a bare, unguarded [fullSync] download.
     */
    private val collectionFile: (() -> File)? = null,
) {

    private companion object {
        /** The three FULL_* outcomes that block normal sync (proto-identical to the bridge). */
        val FULL_REQUIRED = setOf(
            SyncCollectionResponse.ChangesRequired.FULL_SYNC,
            SyncCollectionResponse.ChangesRequired.FULL_DOWNLOAD,
            SyncCollectionResponse.ChangesRequired.FULL_UPLOAD,
        )
    }

    /** In-memory auth cache; null until a successful login, dropped on any failure. */
    private var auth: SyncAuth? = null

    private val _needsAttention = MutableStateFlow(false)

    /**
     * Latches true when a normal sync reports a FULL_* requirement. While true, [sync]
     * short-circuits. Cleared by a successful [fullSync] (which resolves the divergence).
     */
    val needsAttention: StateFlow<Boolean> = _needsAttention.asStateFlow()

    private val _lastSync = MutableStateFlow<Long?>(null)

    /** Epoch millis of the last clean normal sync, or null if none has succeeded yet. */
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    /** True only when all three sync-config fields are non-blank. */
    open val configured: Boolean
        get() = config.endpoint.isNotBlank() &&
            config.username.isNotBlank() &&
            config.password.isNotBlank()

    /**
     * Logs in (if not already cached) and returns the [SyncAuth]. MUST be called on
     * [EngineHolder.lane]. Mirrors `SyncManager._login`: the auth is memoised so repeated
     * syncs reuse one login.
     */
    private fun loginOnLane(): SyncAuth {
        auth?.let { return it }
        val request = SyncLoginRequest.newBuilder()
            .setUsername(config.username)
            .setPassword(config.password)
            .setEndpoint(config.endpoint)
            .build()
        return holder.backend().syncLogin(request).also { auth = it }
    }

    /**
     * Logs in and caches the auth, returning it. Convenience for callers/tests; the
     * normal path is [sync], which logs in lazily. Confines to [EngineHolder.lane].
     */
    open suspend fun login(): SyncAuth = withContext(holder.lane) { loginOnLane() }

    /**
     * Normal collection sync (`syncCollection`), non-fatal by contract. Returns
     * [SyncInfo] describing the outcome; never throws for a network/auth failure.
     *
     * Mirrors `SyncManager.sync`:
     *   - not configured           → `SyncInfo(false, "sync not configured")`;
     *   - already needs attention  → `SyncInfo(false, "needs attention: full sync required")`
     *     (short-circuit, no server round-trip);
     *   - any exception            → drop cached auth, `SyncInfo(false, "sync failed: …")`;
     *   - FULL_* required          → latch [needsAttention], return the needs-attention detail;
     *   - otherwise                → stamp [lastSync], `SyncInfo(true, "ok")`.
     *
     * [media] toggles media sync (studyStart/finish pass `true`; the periodic job may too).
     */
    open suspend fun sync(media: Boolean = true): SyncInfo {
        if (!configured) return SyncInfo(synced = false, detail = "sync not configured")
        if (_needsAttention.value) {
            return SyncInfo(synced = false, detail = "needs attention: full sync required")
        }
        return withContext(holder.lane) {
            val out = try {
                holder.backend().syncCollection(loginOnLane(), media)
            } catch (t: Throwable) {
                auth = null // force a fresh login next attempt (v1 SyncManager semantics)
                return@withContext SyncInfo(synced = false, detail = "sync failed: ${t.message ?: t}")
            }
            if (out.required in FULL_REQUIRED) {
                _needsAttention.value = true
                persistNeedsAttention(true) // durable latch: Home routes to attention across restarts
                return@withContext SyncInfo(
                    synced = false,
                    detail = "needs attention: full sync required",
                )
            }
            _lastSync.value = System.currentTimeMillis()
            SyncInfo(synced = true, detail = "ok")
        }
    }

    /**
     * Full one-way collection transfer (`fullUploadOrDownload`), used to resolve a FULL_*
     * divergence. On success it clears [needsAttention].
     *
     * ## Choreography — VERIFIED against AnkiDroid (github.com/ankidroid/Anki-Android)
     * `com/ichi2/anki/Sync.kt` `handleUpload`/`handleDownload` drive it as:
     * ```
     * close(downgrade = false, forFullSync = true)
     * try { fullUploadOrDownload(auth, upload, serverUsn = mediaUsn) }
     * finally { reopen(afterFullSync = true) }
     * ```
     * Crucially, at the *backend* level `close(forFullSync = true)` does NOT call
     * `backend.closeCollection` (libanki `Collection.close`: the `if (!forFullSync)` guard
     * skips it — "backend will take care of collection close"), and `reopen(afterFullSync
     * = true)` does NOT call `backend.openCollection` again (libanki `Storage.openDB`: when
     * `afterFullSync` it sets `create = false` and skips `openCollection` — the backend
     * reopened the collection internally during the transfer). Confirmed against pylib
     * `anki/collection.py` `close_for_full_sync`/`reopen(after_full_sync=True)`, which the
     * bridge's `_operator_bootstrap` (test_study_sync.py) uses identically.
     *
     * Therefore, with the RAW rsdroid [net.ankiweb.rsdroid.Backend] that [EngineHolder]
     * exposes (which IS the collection handle — there is no separate DB proxy to drop or
     * reconnect), the correct mirror is a bare `fullUploadOrDownload`: NO
     * `closeCollection(false)` before it (that would tell the backend to release the
     * collection, contradicting "backend takes care of it") and NO `openCollection` after
     * (the backend has already reopened it). [EngineHolder.openCollectionPath] stays valid
     * throughout, so the engine is immediately usable again.
     *
     * `serverUsn` is left unset (mirrors the bridge's `server_usn=None`; the proto field is
     * optional and AnkiDroid only sets it when a media USN is known).
     */
    open suspend fun fullSync(upload: Boolean) {
        withContext(holder.lane) {
            val backend = holder.backend()
            val request = FullUploadOrDownloadRequest.newBuilder()
                .setAuth(loginOnLane())
                .setUpload(upload)
                .build()
            backend.fullUploadOrDownload(request)
            // The backend reopened the collection internally (afterFullSync semantics);
            // the resolved divergence clears both the in-memory and the durable latch.
            _needsAttention.value = false
            persistNeedsAttention(false)
        }
    }

    /**
     * A guarded full DOWNLOAD (`fullUploadOrDownload(upload=false)`) that will not silently
     * wipe a populated phone with an EMPTY server collection.
     *
     * rsdroid exposes no cheap pre-check for the server's collection size (the sync protos
     * carry only `required`/USN; rslib's `SyncMeta empty`/`collection_bytes` are not
     * surfaced — verified via javap on the backend), so the guard validates the RESULT and
     * rolls back: it backs up the collection FILE, downloads, and if a substantial local
     * collection was replaced by an empty one (and the caller did not [force]) it restores
     * the backup so the phone is left intact. A mid-download failure restores likewise. See
     * [FullDownloadFlow] for the orchestration and [FullDownloadGuard] for the decision.
     *
     * On a kept download (server non-empty, or [force]d) this clears the needs-attention
     * latch exactly like [fullSync]. On a trip or failure the latch is untouched — the
     * divergence is unresolved and the UI must re-decide.
     *
     * When [collectionFile] is null the guard cannot protect a file, so this degrades to a
     * bare [fullSync] download and returns [FullDownloadResult.Downloaded]/[FullDownloadResult.Failed].
     */
    open suspend fun fullDownload(force: Boolean): FullDownloadResult {
        val fileProvider = collectionFile ?: run {
            return try {
                fullSync(upload = false)
                FullDownloadResult.Downloaded
            } catch (t: Throwable) {
                FullDownloadResult.Failed(t.message ?: t.toString())
            }
        }
        return withContext(holder.lane) {
            val ops = RealFullDownloadOps(fileProvider)
            FullDownloadFlow.run(ops, force).also { result ->
                if (result is FullDownloadResult.Downloaded) {
                    ops.discardBackup() // kept the download — the safety copy is no longer needed
                    _needsAttention.value = false
                    persistNeedsAttention(false)
                }
            }
        }
    }

    /**
     * The real, lane-confined [FullDownloadOps] over rslib + the collection file. Every
     * method here is already on [holder.lane] (the caller wraps the whole flow), so it
     * touches the native backend directly.
     */
    private inner class RealFullDownloadOps(private val file: () -> File) : FullDownloadOps {
        private val backup: File get() = GuardBackupRecovery.backupOf(file())

        override suspend fun localCardCount(): Int =
            holder.backend().searchCards("", anki.search.SortOrder.getDefaultInstance()).size

        override suspend fun backupCollection() {
            // Close for a consistent on-disk snapshot (flush WAL), copy, then reopen.
            val path = file().absolutePath
            holder.closeCollection()
            file().copyTo(backup, overwrite = true)
            holder.reopenCollection(path)
        }

        override suspend fun download() {
            val request = FullUploadOrDownloadRequest.newBuilder()
                .setAuth(loginOnLane())
                .setUpload(false)
                .build()
            holder.backend().fullUploadOrDownload(request) // backend reopens internally
        }

        override suspend fun restoreCollection() {
            // Crash-safe roll-back: recover from the backup with the SAME journaling copy
            // (copy → fsync → atomic rename → delete backup) that [GuardBackupRecovery]
            // runs at open time, so a crash mid-restore leaves the backup intact for the
            // next-open recovery instead of a torn file (findings #2/#3).
            val path = file().absolutePath
            holder.closeCollection()
            GuardBackupRecovery.recover(file())
            holder.reopenCollection(path)
        }

        /** Remove the safety copy after a kept download (nothing to roll back to any more). */
        fun discardBackup() {
            backup.delete()
        }
    }

    /** Test-only: the in-memory cached auth (null after a login failure). */
    internal fun cachedAuthForTest(): SyncAuth? = auth

    /** Test-only: force the [needsAttention] latch to a given value. */
    internal fun setNeedsAttentionForTest(value: Boolean) {
        _needsAttention.value = value
    }
}
