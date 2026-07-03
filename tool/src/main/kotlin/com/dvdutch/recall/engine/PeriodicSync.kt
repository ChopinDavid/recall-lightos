package com.dvdutch.recall.engine

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.SealedLightContext
import com.dvdutch.recall.api.SyncInfo
import kotlin.time.Duration.Companion.minutes

/**
 * Background collection sync on a fixed cadence, so reviews propagate between the phone
 * and any other Anki client even when a study session is not open.
 *
 * ## Scheduling — the SDK's LightWork ([com.thelightphone.sdk.LightWork])
 * Registered as a top-level `@LightJob` (per the client README / `LightWork.kt` example)
 * and scheduled with [com.thelightphone.sdk.LightWork.enqueuePeriodic]. WorkManager's
 * floor is 15 minutes; we use [PERIOD] = 60 minutes (well above the floor — sync is not
 * time-sensitive, and a large-battery-friendly cadence matches the SDK's guidance that
 * LightWork jobs "aren't great for anything time-sensitive"). `enqueueUniquePeriodicWork`
 * with `UPDATE` policy means re-scheduling is idempotent: there is always exactly one
 * periodic schedule under [JOB_KEY].
 *
 * ## Lane safety — no explicit gating needed
 * The job must sync only when a session isn't mid-flight on the engine. It gets this for
 * free: [SyncController.sync] confines every backend touch to [EngineHolder.lane], a
 * `limitedParallelism(1)` serial dispatcher shared by ALL engine work in the app. If a
 * study session holds the lane, the job's `sync()` simply queues behind it and runs when
 * the session yields — the native backend is never entered re-entrantly. There is nothing
 * extra to serialize; routing through the one lane is the whole guarantee.
 *
 * ## Result mapping
 * [runOnce] maps a [SyncController]'s outcome onto a [LightJobResult]:
 *   - a clean sync                → [LightJobResult.Success];
 *   - needsAttention (FULL_*)     → [LightJobResult.Success]: a retry cannot resolve it
 *     (resolution is out-of-band, via a full sync); retrying would only burn battery;
 *   - a non-fatal sync failure    → [LightJobResult.Retry]: transient (flaky network,
 *     server down), so WorkManager reschedules with backoff — exactly the SDK's intent
 *     for [LightJobResult.Retry];
 *   - no controller (unconfigured)→ [LightJobResult.Success]: nothing to do.
 *
 * ## Wiring status
 * The [recallPeriodicSync] handler is the SDK entry point; its collection-path and
 * sync-config resolution is completed in Task 4 (prefs rewrite), which supplies a
 * configured [SyncController] to [runOnce]. Until then the handler resolves via
 * [controllerProvider], which defaults to "unconfigured" (returns null → Success), so the
 * job is schedulable and green without prematurely reaching into the bridge-era prefs.
 */
object PeriodicSync {

    /** Unique LightWork key for the periodic sync schedule. */
    const val JOB_KEY = "recall-periodic-sync"

    /** Sync cadence. Above WorkManager's 15-minute floor; sync is not time-sensitive. */
    val PERIOD = 60.minutes

    /**
     * Resolves the [SyncController] to sync with, or null when sync is unconfigured.
     * Task 4 replaces the default with a prefs-backed resolver; overridable for tests.
     */
    var controllerProvider: (SealedLightContext) -> SyncController? = { null }

    /**
     * Schedule the periodic sync. Idempotent (UPDATE policy): calling it repeatedly keeps
     * exactly one schedule. Returns [com.thelightphone.sdk.LightWork.enqueuePeriodic]'s
     * result (false only if no `@LightJob` is registered for [JOB_KEY]).
     */
    fun schedule(lightContext: SealedLightContext): Boolean =
        com.thelightphone.sdk.LightWork.enqueuePeriodic(lightContext, JOB_KEY, PERIOD)

    /**
     * The pure, testable core of one periodic run. Given the resolved [controller]
     * (null when unconfigured), performs one sync and maps the outcome to a
     * [LightJobResult] per the class contract. Never throws — [SyncController.sync] is
     * itself non-fatal, so this only interprets its [SyncInfo].
     */
    suspend fun runOnce(controller: SyncController?): LightJobResult {
        if (controller == null) return LightJobResult.Success()
        if (controller.needsAttention.value) return LightJobResult.Success()
        val info: SyncInfo = controller.sync(media = true)
        return when {
            info.synced -> LightJobResult.Success()
            // A FULL_* just latched — a retry cannot resolve it; do not reschedule early.
            controller.needsAttention.value -> LightJobResult.Success()
            // Otherwise the failure was transient (network/server); back off and retry.
            else -> LightJobResult.Retry
        }
    }
}

/**
 * The registered `@LightJob` handler for periodic sync. Thin glue: resolves the
 * [SyncController] for this run via [PeriodicSync.controllerProvider] and delegates the
 * decision to [PeriodicSync.runOnce]. Scheduled with [PeriodicSync.schedule].
 */
@LightJob(PeriodicSync.JOB_KEY)
val recallPeriodicSync: LightJobHandler = { ctx, _ ->
    PeriodicSync.runOnce(PeriodicSync.controllerProvider(ctx))
}
