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
 */
sealed class BridgeError : Exception() {
    /** The engine/sync layer could not complete the operation (e.g. a sync round-trip failed). */
    data object Unreachable : BridgeError()

    /** Sync auth was rejected — the token is invalid or missing. Never auto-retry. */
    data object Unauthorized : BridgeError()

    /** A FULL_* divergence latched: the collection needs an out-of-band full up/download. */
    data object NeedsAttention : BridgeError()
}
