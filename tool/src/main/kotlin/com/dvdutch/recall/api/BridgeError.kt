package com.dvdutch.recall.api

/**
 * The closed set of failures a [BridgeClient] call can surface.
 *
 * Every non-success outcome is mapped to exactly one of these before it leaves
 * the client, so callers never see raw Ktor/IO exceptions and can exhaustively
 * `when` over the taxonomy.
 */
sealed class BridgeError : Exception() {
    /** Connect/IO failure — the bridge could not be reached at all. */
    data object Unreachable : BridgeError()

    /** HTTP 401 — the token is invalid or missing. Never auto-retry. */
    data object Unauthorized : BridgeError()

    /** The response's `X-Bridge-Api` header was absent or not `"1"`. */
    data class VersionSkew(val got: String?) : BridgeError()

    /** HTTP 503 whose body carries the `needs_attention` shape. */
    data object NeedsAttention : BridgeError()

    /** Any other non-2xx response; [message] is shown to the operator verbatim. */
    data class Server(override val message: String) : BridgeError()
}
