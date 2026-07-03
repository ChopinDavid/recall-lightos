package com.dvdutch.recall.audio

import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.Executors

/**
 * The minimal playback surface [CardAudioPlayer] drives. Modelling only the six calls the
 * queue needs lets the sequential state machine be unit-tested against a fake that fires its
 * completion callback on demand — the real [android.media.MediaPlayer] wrapper
 * ([RealMediaPlayback]) needs the Android runtime and is emulator-verified in Task 3.
 */
interface MediaPlayback {
    fun setDataSource(path: String)
    fun prepare()
    fun start()
    fun setOnCompletion(cb: () -> Unit)
    fun stop()
    fun release()
}

/**
 * Plays an ordered list of on-device media files sequentially, mirroring the media-resolution
 * path used by `MediaImage`/`MediaLoader`: a bare filename is turned into an on-disk [File]
 * via [resolve] (production passes `RecallStorage::mediaFile`), and a missing file is skipped
 * rather than crashing the review — the same "never silently break the review" principle.
 *
 * Playback advances one track at a time: the next track starts only when the previous one's
 * completion callback fires. [MediaPlayer.prepare] can block, so every player mutation runs on
 * a dedicated single-thread [runner] (default: a single-thread executor) off the caller's
 * thread — never on `EngineHolder.lane`, since this is not a backend call.
 *
 * **Serialized player mutations (the double-grade race):** all state that a running player can
 * touch — the current player, the queue, and an epoch token — is mutated only on [runner], the
 * single serialization point. Each `play`/`stop`/`release` bumps [epoch]; a player captures the
 * epoch it was started under and its completion callback is inert if the epoch has since moved
 * on. So a new `play()` arriving mid-playback reliably stops+releases the old player and a stale
 * late completion from a replaced/stopped/released player can never advance the current queue.
 *
 * @param resolve bare media name → on-disk [File] (or null when unknown); production uses
 *   `RecallStorage::mediaFile`. A returned file that does not exist is treated as missing.
 * @param factory builds a fresh [MediaPlayback] per track; production builds [RealMediaPlayback].
 * @param runner off-main serialization seam; production is a single-thread executor, tests inject
 *   a synchronous inline runner so the queue is asserted deterministically.
 */
class CardAudioPlayer(
    private val resolve: (String) -> File?,
    private val factory: () -> MediaPlayback = { RealMediaPlayback() },
    private val runner: (Runnable) -> Unit =
        Executors.newSingleThreadExecutor { r -> Thread(r, "CardAudioPlayer").apply { isDaemon = true } }
            .let { exec -> { work: Runnable -> exec.execute(work) } },
) {
    /** The player currently prepared/started, if any. Mutated only on [runner]. */
    private var current: MediaPlayback? = null

    /** Remaining tracks to play after the current one completes. Mutated only on [runner]. */
    private var queue: ArrayDeque<String> = ArrayDeque()

    /**
     * Monotonic token identifying the active playback session. Bumped by every `play`/`stop`/
     * `release` so a completion callback captured under an older epoch becomes a no-op.
     */
    private var epoch: Long = 0

    /** Starts (or restarts) playback of [filenames] sequentially. Missing files are skipped. */
    fun play(filenames: List<String>) {
        val toPlay = filenames.toList()
        runner {
            stopCurrentLocked()
            val session = ++epoch
            queue = ArrayDeque(toPlay)
            advanceLocked(session)
        }
    }

    /** Stops and releases the current player and cancels the queue. Idempotent. */
    fun stop() {
        runner {
            stopCurrentLocked()
            ++epoch
            queue.clear()
        }
    }

    /**
     * Releases the current player and cancels the queue. Idempotent, and the player stays usable
     * for a later [play]. (With a per-track player there is nothing extra to tear down beyond a
     * [stop]; the two share the same cleanup.)
     */
    fun release() {
        stop()
    }

    /** Stops+releases [current] if present and clears the reference. Runs only on [runner]. */
    private fun stopCurrentLocked() {
        current?.let { player ->
            player.stop()
            player.release()
        }
        current = null
    }

    /**
     * Pops entries until one resolves to an existing file, plays it, and arms its completion
     * callback to advance the queue. Skips entries that don't resolve or whose file is missing.
     * Runs only on [runner]; [session] is the epoch this advance belongs to.
     */
    private fun advanceLocked(session: Long) {
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val file = resolve(name)
            if (file == null || !file.isFile) continue // missing media → skip gracefully
            val player = factory()
            current = player
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletion {
                runner {
                    // Inert if this player was replaced/stopped/released in the meantime.
                    if (session != epoch) return@runner
                    current?.release()
                    current = null
                    advanceLocked(session)
                }
            }
            player.start()
            return
        }
        // Nothing left to play in this session.
        if (session == epoch) current = null
    }
}

/**
 * Production [MediaPlayback]: a thin passthrough over [android.media.MediaPlayer]. Not unit-tested
 * (the Android runtime is required); emulator-verified in Task 3. Keep it obvious — the queue
 * logic and its races live in [CardAudioPlayer] and are covered by the fake.
 */
class RealMediaPlayback : MediaPlayback {
    private val player = MediaPlayer()

    override fun setDataSource(path: String) = player.setDataSource(path)
    override fun prepare() = player.prepare()
    override fun start() = player.start()
    override fun setOnCompletion(cb: () -> Unit) {
        player.setOnCompletionListener { cb() }
    }
    override fun stop() = player.stop()
    override fun release() = player.release()
}
