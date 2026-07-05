package com.dvdutch.recall.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dvdutch.recall.engine.EngineHolder
import com.dvdutch.recall.engine.FullDownloadResult
import com.dvdutch.recall.engine.RecallEngine
import com.dvdutch.recall.engine.SyncConfig
import com.dvdutch.recall.engine.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Shared fakes + helpers for the ViewModel-layer unit tests. Every ViewModel builds its
 * [RecallEngine] and drives its coroutines through injectable seams (default-args in
 * production); these fakes plug into those seams so the destructive divergence /
 * download / login / deck-load flows are asserted on the JVM with no native backend,
 * no Android runtime and no [android.media.MediaPlayer]. See each *ViewModelTest for use.
 */

/** A temp dir that is fresh per call; caller deletes it recursively when done. */
internal fun tmpDir(prefix: String): File =
    File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

/**
 * A REAL preferences DataStore over a temp file, driven by [scope]. Used where a test
 * asserts an actual persistence round-trip (Settings save, Home expand toggle, first-run
 * config write) rather than mocking the store.
 */
internal fun newDataStore(scope: CoroutineScope, dir: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }

/**
 * Polls [predicate] up to [timeoutMs], sleeping [stepMs] between checks. The ViewModels'
 * work runs on injected dispatchers/scope and DataStore's own internal dispatchers, so a
 * bounded poll is the dependency-free way (no kotlinx-coroutines-test on the classpath) to
 * await an emitted UI state deterministically. Throws with [message] on timeout.
 */
internal fun waitFor(timeoutMs: Long = 5_000, stepMs: Long = 5, message: () -> String = { "condition" }, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(stepMs)
    }
    if (!predicate()) throw AssertionError("timed out waiting for ${message()}")
}

private val CONFIGURED = SyncConfig("https://sync.example/", "user", "pass")

/**
 * A [SyncController] test double. It subclasses the real controller (constructed over the
 * process [EngineHolder] but NEVER touching the backend, since every overridden method is
 * scripted) and records the destructive calls the ViewModels make on it.
 */
internal open class FakeSyncController(
    private val configuredResult: Boolean = true,
) : SyncController(CONFIGURED, EngineHolder) {

    var loginCalls = 0
    var loginError: Throwable? = null

    var fullSyncCalls = 0
    val fullSyncUploadArgs = mutableListOf<Boolean>()
    var fullSyncError: Throwable? = null

    var fullDownloadCalls = 0
    val fullDownloadForceArgs = mutableListOf<Boolean>()
    var fullDownloadResult: FullDownloadResult = FullDownloadResult.Downloaded

    // Scripted normal sync(): count calls, script the returned SyncInfo, and — when a
    // FULL_* divergence should be simulated — latch needsAttention like the real one does.
    @Volatile var syncCalls = 0
    var syncResult: com.dvdutch.recall.api.SyncInfo = com.dvdutch.recall.api.SyncInfo(synced = true, detail = "ok")
    var syncLatchesAttention: Boolean = false
    // Optional SUSPENDING gate so a test can hold a sync in-flight (icon ghosted) and assert
    // the no-double-fire / ghosting behaviour deterministically. Suspends the sync coroutine
    // (never blocks a thread — so it composes with any injected dispatcher) until [releaseSync].
    private val syncGate = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Unit>?>(null)
    fun blockNextSync() { syncGate.set(kotlinx.coroutines.CompletableDeferred()) }
    fun releaseSync() { syncGate.getAndSet(null)?.complete(Unit) }

    override suspend fun sync(media: Boolean): com.dvdutch.recall.api.SyncInfo {
        syncCalls++
        syncGate.get()?.await()
        if (syncLatchesAttention) setNeedsAttentionForTest(true)
        return syncResult
    }

    override val configured: Boolean get() = configuredResult

    override suspend fun login(): anki.sync.SyncAuth {
        loginCalls++
        loginError?.let { throw it }
        return anki.sync.SyncAuth.getDefaultInstance()
    }

    override suspend fun fullSync(upload: Boolean) {
        fullSyncCalls++
        fullSyncUploadArgs.add(upload)
        fullSyncError?.let { throw it }
    }

    override suspend fun fullDownload(force: Boolean): FullDownloadResult {
        fullDownloadCalls++
        fullDownloadForceArgs.add(force)
        return fullDownloadResult
    }
}

/**
 * A [RecallEngine] test double. Overrides only the collection-open / controller / deck /
 * card-count seams the ViewModels reach through; nothing touches rslib. [openCalls]
 * records that a destructive flow opened the collection before acting.
 */
internal open class FakeEngine(
    filesDir: File,
    dataStore: DataStore<Preferences>,
    private val controller: SyncController = FakeSyncController(),
    private val cardCount: Int? = 7,
    private val decks: List<com.dvdutch.recall.api.Deck> = emptyList(),
    private val collectionPresent: Boolean = true,
    private val durableNeedsAttention: Boolean = false,
) : RecallEngine(filesDir, dataStore) {

    var openCalls = 0
    var openError: Throwable? = null

    init {
        // Home routes on storage.collectionExists(); materialise (or not) the file so the
        // real RecallStorage reports the intended presence without a native backend.
        if (collectionPresent) {
            storage.collectionDir.mkdirs()
            storage.collectionFile.writeBytes(byteArrayOf(1, 2, 3))
        }
    }

    override suspend fun openCollection(): String {
        openCalls++
        openError?.let { throw it }
        return "test-collection"
    }

    override suspend fun controller(): SyncController = controller

    override suspend fun localCardCount(): Int = cardCount ?: throw IllegalStateException("no count")

    override suspend fun needsAttention(): Boolean = durableNeedsAttention

    override suspend fun decks(sync: SyncController?): List<com.dvdutch.recall.api.Deck> = decks
}

/**
 * Runs [block] with a fresh temp dir + real DataStore, cleaning up after. The DataStore
 * runs on a DEDICATED background scope (its own IO dispatcher + Job), NOT the [runBlocking]
 * thread — so a test's [waitFor] `Thread.sleep` polling never starves the DataStore's own
 * write/read actor (which would otherwise deadlock a persist round-trip). The `scope` handed
 * to [block] is that same background scope, suitable for driving the DataStore directly.
 */
internal fun <T> withEnv(prefix: String, block: (dir: File, scope: CoroutineScope, dataStore: DataStore<Preferences>) -> T): T {
    val dir = tmpDir(prefix)
    val bgScope = CoroutineScope(Dispatchers.IO + Job())
    return try {
        runBlocking { block(dir, bgScope, newDataStore(bgScope, dir)) }
    } finally {
        bgScope.cancel()
        dir.deleteRecursively()
    }
}
