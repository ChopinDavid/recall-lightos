package com.dvdutch.recall.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows the Anki engine's media sync, which runs in the background: a sync that
 * includes media ([SyncController.sync] with `media = true`, or
 * [SyncController.startMediaSync]) returns as soon as the collection is done, while
 * images and audio keep downloading. Until they arrive a card can't show them, so the
 * app needs to know: Home says media is still downloading, a missing image says so too,
 * and it tries again as files come in.
 *
 * [watch] starts polling [isActive] (the engine's `mediaSyncStatus().active`) every
 * [pollMillis] until the sync is done. [active] is true meanwhile; [arrivals] goes up on
 * every poll and once more at the end, since new files may have landed.
 *
 * One tracker serves the whole app ([shared]): a sync started anywhere — Home, a study
 * session, the hourly job, first-run — shows up everywhere.
 */
class MediaSyncTracker(
    private val isActive: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val _active = MutableStateFlow(false)

    /** True while a media sync is running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _arrivals = MutableStateFlow(0)

    /**
     * Goes up each time media files may have arrived (every poll during a sync, and once
     * when it ends): an image that wasn't there yet is worth trying again.
     */
    val arrivals: StateFlow<Int> = _arrivals.asStateFlow()

    // The poll loop, and whether a watch() came in while it was running. Both guarded by
    // this tracker's lock, so a loop that is about to stop can't miss a sync started just
    // then, and an old loop's cleanup can't undo a newer watch.
    private var job: Job? = null
    private var rewatched = false

    /**
     * Starts following a media sync that was just started, unless already following one.
     * Never throws: a status read that fails ends the watch as if the sync were done.
     */
    fun watch() {
        synchronized(this) {
            if (job?.isActive == true) {
                rewatched = true
                return
            }
            rewatched = false
            // The caller just started a sync, so it's running until the engine says otherwise.
            _active.value = true
            job = scope.launch { poll() }
        }
    }

    private suspend fun poll() {
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                val running = try {
                    isActive()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                _arrivals.update { it + 1 }
                if (!running && stop(self)) return
                delay(pollMillis)
            }
        } finally {
            release(self)
        }
    }

    /** True to stop: unless a watch() came in since the last read, which gets one more. */
    private fun stop(self: Job?): Boolean = synchronized(this) {
        if (rewatched) {
            rewatched = false
            return false
        }
        release(self)
        true
    }

    /** Stands this loop down, unless a newer one has taken over. */
    private fun release(self: Job?) {
        synchronized(this) {
            if (job === self) {
                job = null
                _active.value = false
            }
        }
    }

    companion object {
        /** How often a running media sync is checked on. */
        const val POLL_MILLIS = 1_000L

        /** The app's tracker, over the engine's own media-sync status. */
        val shared: MediaSyncTracker by lazy {
            MediaSyncTracker(
                isActive = { withContext(EngineHolder.lane) { EngineHolder.backend().mediaSyncStatus().active } },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
}

/**
 * True once this flow has been true for [millis] without a break, false the moment it
 * isn't — so a media sync that only checks in with the server for a moment (nothing new
 * to download) doesn't flash a "downloading" line on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.heldFor(millis: Long): Flow<Boolean> =
    distinctUntilChanged().transformLatest { on ->
        if (on) {
            delay(millis)
            emit(true)
        } else {
            emit(false)
        }
    }
