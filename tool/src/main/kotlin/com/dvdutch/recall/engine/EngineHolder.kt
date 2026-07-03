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
 * The native library is loaded exactly once, lazily, on first [backend] access, via
 * the injectable [nativeLoader] seam (default `System.loadLibrary("rsdroid")`), then
 * [BackendFactory.getBackend]. On the desktop JVM (unit tests) the library is instead
 * loaded by the `-testing` artifact's `RustBackendLoader.ensureSetup()`, which tests
 * install by overriding [nativeLoader] before the first [backend] call — see
 * `EngineSmokeTest`.
 *
 * Thread-safety contract: [backend], [openCollection] and [closeCollection] are NOT
 * internally synchronized. They are safe ONLY when confined to the serial [lane] —
 * callers MUST wrap every engine interaction in `withContext(EngineHolder.lane) { ... }`.
 * A caller that touches the native handle off-lane gets no thread-safety guarantee and
 * can re-enter the backend concurrently (undefined behaviour in rslib).
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

    /**
     * The native-load step, invoked exactly once before the backend is first created.
     * Defaults to `System.loadLibrary("rsdroid")` for on-device use. It is a
     * test-overridable seam: JVM unit tests set it to `RustBackendLoader.ensureSetup()`
     * (which loads the desktop native) in place of `loadLibrary`.
     *
     * Set-before-first-[backend]-call only: once [backend] has run the loader (and set
     * [libraryLoaded]), reassigning this has no effect — the library is already resident.
     */
    internal var nativeLoader: () -> Unit = { System.loadLibrary("rsdroid") }

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
     * The process-wide rslib [Backend], created on first access. Runs [nativeLoader]
     * once (default `System.loadLibrary("rsdroid")`) before constructing it. Not
     * itself synchronized: all access MUST be serialized through [lane].
     */
    fun backend(): Backend {
        backendInstance?.let { return it }
        if (!libraryLoaded) {
            nativeLoader()
            libraryLoaded = true
        }
        return BackendFactory.getBackend().also { backendInstance = it }
    }

    /**
     * Opens the collection at [path] (creating it if absent). Closes any collection
     * already open first, so this is idempotent with respect to the active
     * collection. Lane-confined by contract: MUST be called on [lane]
     * (`withContext(EngineHolder.lane) { ... }`); off-lane callers get no thread-safety.
     */
    fun openCollection(path: String) {
        if (openCollectionPath == path) return
        if (collectionOpen) closeCollection()
        backend().openCollection(path)
        openCollectionPath = path
    }

    /**
     * Closes the open collection, if any. The backend handle itself is retained for
     * reuse. No-op when no collection is open. Lane-confined by contract: MUST be
     * called on [lane] (`withContext(EngineHolder.lane) { ... }`); off-lane callers get
     * no thread-safety.
     */
    fun closeCollection() {
        if (!collectionOpen) return
        backendInstance?.closeCollection(false)
        openCollectionPath = null
    }
}
