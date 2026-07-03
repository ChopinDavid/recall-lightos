package com.dvdutch.recall.api

/**
 * The closed set of failures a study/sync operation can surface on-device.
 *
 * Kept as a sealed taxonomy so callers ([com.dvdutch.recall.study.StudyMachine]'s
 * `guard`, the Settings/Study copy mappers) can exhaustively `when` over it. The
 * former network-only variants (version-header skew, raw HTTP status detail,
 * transport unreachability) retired with [com.dvdutch.recall.engine.LocalEngineApi]'s
 * move on-device — the engine either resolves an operation locally or raises one
 * of the variants below.
 *
 * Producer note (honest): of the variants below only [NeedsAttention] has a live
 * production producer today ([com.dvdutch.recall.engine.LocalEngineApi]).
 * [Unreachable] and [Unauthorized] have consumers — SettingsMessages copy,
 * StudyScreen, and StudyMachine's `guard` all `when` over them — but no
 * production code raises them: SyncController deliberately swallows sync failures
 * into non-fatal `SyncInfo` strings (the sync layer is intentionally best-effort,
 * not fatal). They are retained as defensive seams and as fixtures the consumer
 * tests exercise; do NOT wire new producers here without revisiting that
 * swallow-into-SyncInfo design.
 */
sealed class BridgeError : Exception() {
    /**
     * The engine/sync layer could not complete the operation (e.g. a sync
     * round-trip failed). Currently producer-less — see the class KDoc: sync
     * failures are swallowed into `SyncInfo` rather than raised.
     */
    data object Unreachable : BridgeError()

    /**
     * Sync auth was rejected — the token is invalid or missing. Never auto-retry.
     * Currently producer-less — see the class KDoc.
     */
    data object Unauthorized : BridgeError()

    /** A FULL_* divergence latched: the collection needs an out-of-band full up/download. */
    data object NeedsAttention : BridgeError()
}
