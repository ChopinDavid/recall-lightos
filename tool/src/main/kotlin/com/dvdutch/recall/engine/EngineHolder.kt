package com.dvdutch.recall.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendFactory

/**
 * The single owner of the on-device Anki engine (rslib via JNI).
 *
 * The rslib [Backend] is a native handle and is NOT safe to touch concurrently,
 * so — exactly like [com.dvdutch.recall.study.StudyMachine]'s driver in
 * [com.dvdutch.recall.ui.StudyViewModel] — every engine call project-wide is
 * confined to one serial lane: [lane], a `Dispatchers.Default.limitedParallelism(1)`
 * dispatcher. `limitedParallelism(1)` guarantees at most one coroutine runs on it
 * at a time, so all `withContext(EngineHolder.lane) { ... }` engine calls form a
 * single queue and the native backend is never entered re-entrantly. There is ONE
 * lane for the whole engine, not one per collection.
 *
 * The native library is loaded exactly once, lazily, on first [backend] access:
 * `System.loadLibrary("rsdroid")` then [BackendFactory.getBackend]. On the desktop
 * JVM (unit tests) the library is instead loaded by the `-testing` artifact's
 * `RustBackendLoader.ensureSetup()`; this object's own `loadLibrary` is a no-op in
 * that case (the lib is already resident) — see `EngineSmokeTest`.
 *
 * This is deliberately minimal; later tasks grow it (query/answer/sync helpers).
 * Callers must route through [lane]; this object does not spawn coroutines itself.
 */
object EngineHolder {

    /**
     * The serial confinement lane for EVERY engine call in the app. Callers wrap
     * their backend interactions in `withContext(EngineHolder.lane) { ... }` so the
     * native handle is only ever entered from one coroutine at a time.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val lane: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Loaded once on first [backend] access; guards against a double loadLibrary. */
    private var libraryLoaded = false

    /** The process-wide backend, created lazily on first [backend] access. */
    private var backendInstance: Backend? = null

    /** The currently open collection's path, or null when none is open. */
    private var openCollectionPath: String? = null

    /** True while a collection is open. */
    val collectionOpen: Boolean
        get() = openCollectionPath != null

    /**
     * The process-wide rslib [Backend], created on first access. Loads the native
     * library once (`System.loadLibrary("rsdroid")`) before constructing it. Not
     * itself synchronized: all access is expected to be serialized through [lane].
     */
    fun backend(): Backend {
        backendInstance?.let { return it }
        if (!libraryLoaded) {
            System.loadLibrary("rsdroid")
            libraryLoaded = true
        }
        return BackendFactory.getBackend().also { backendInstance = it }
    }

    /**
     * Opens the collection at [path] (creating it if absent). Closes any collection
     * already open first, so this is idempotent with respect to the active
     * collection. Must be called on [lane].
     */
    fun openCollection(path: String) {
        if (openCollectionPath == path) return
        if (collectionOpen) closeCollection()
        backend().openCollection(path)
        openCollectionPath = path
    }

    /**
     * Closes the open collection, if any. The backend handle itself is retained for
     * reuse. No-op when no collection is open. Must be called on [lane].
     */
    fun closeCollection() {
        if (!collectionOpen) return
        backendInstance?.closeCollection(false)
        openCollectionPath = null
    }
}
