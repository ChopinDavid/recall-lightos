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
class SyncController(
    private val config: SyncConfig,
    private val holder: EngineHolder,
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
    val configured: Boolean
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
    suspend fun login(): SyncAuth = withContext(holder.lane) { loginOnLane() }

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
    suspend fun sync(media: Boolean = true): SyncInfo {
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
    suspend fun fullSync(upload: Boolean) {
        withContext(holder.lane) {
            val backend = holder.backend()
            val request = FullUploadOrDownloadRequest.newBuilder()
                .setAuth(loginOnLane())
                .setUpload(upload)
                .build()
            backend.fullUploadOrDownload(request)
            // The backend reopened the collection internally (afterFullSync semantics);
            // the resolved divergence clears the attention latch.
            _needsAttention.value = false
        }
    }

    /** Test-only: the in-memory cached auth (null after a login failure). */
    internal fun cachedAuthForTest(): SyncAuth? = auth

    /** Test-only: force the [needsAttention] latch to a given value. */
    internal fun setNeedsAttentionForTest(value: Boolean) {
        _needsAttention.value = value
    }
}
