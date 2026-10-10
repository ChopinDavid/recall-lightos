package com.dvdutch.recall.engine

import net.ankiweb.rsdroid.exceptions.BackendNetworkException
import net.ankiweb.rsdroid.exceptions.BackendSyncException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

/**
 * Why a sync or download couldn't complete, classified so the user is told what to do
 * instead of seeing rslib's raw error text.
 *
 * The common case on a phone is a self-hosted server on the user's home network
 * (`http://192.168.x.x:8080/`) while the phone is somewhere else, so a network failure
 * against a private address is checked against the phone's own addresses: no address
 * on the server's subnet means the phone isn't on that network. This needs no
 * permission beyond INTERNET (reading the Wi-Fi name would need location access). It is
 * a strong hint, not proof — a VPN or a router running two subnets can mislead it — so
 * every message says what to try rather than asserting the cause.
 */
sealed class SyncFailure {

    /** The server is on a home network ([host]) the phone isn't currently on. */
    data class NotOnServerNetwork(val host: String) : SyncFailure()

    /** The phone is on the server's network but got no answer from [server]. */
    data class NoAnswerOnNetwork(val server: String) : SyncFailure()

    /** The server is outside the local network (or a hostname) and didn't answer. */
    data class ServerUnreachable(val server: String) : SyncFailure()

    /** The phone has no network address at all. */
    data object Offline : SyncFailure()

    /** The server rejected the username or password. */
    data object AuthRejected : SyncFailure()

    /** Anything else; [message] is the engine's own text. */
    data class Other(val message: String) : SyncFailure()

    /** Whether studying offline and syncing later is the remedy (vs. fixing settings). */
    val retryLater: Boolean
        get() = this is NotOnServerNetwork || this is NoAnswerOnNetwork ||
            this is ServerUnreachable || this is Offline

    /** What went wrong and what to try, without a "sync failed" / "download failed" prefix. */
    val reason: String
        get() = when (this) {
            is NotOnServerNetwork ->
                "this phone isn't on the same network as your sync server ($host). " +
                    "Join the Wi-Fi your computer is on and try again."
            is NoAnswerOnNetwork ->
                "no answer from your sync server at $server. Make sure that computer " +
                    "is on and awake, with the server running."
            is ServerUnreachable ->
                "couldn't reach your sync server at $server. Check your connection " +
                    "and the server address."
            Offline -> "this phone isn't connected to a network."
            AuthRejected -> "username or password not accepted. Check them in settings."
            is Other -> message
        }

    /** The one-line sync status: Home's error line and the session summary's small print. */
    val syncDetail: String
        get() = "sync failed — $reason" +
            if (retryLater) " Your reviews are saved on this phone." else ""

    companion object {
        /**
         * Classifies [error] from a login/sync/download against [endpoint]. [networks]
         * lists the phone's current IPv4 networks; it is injectable for tests and defaults
         * to the device's real interfaces.
         */
        fun classify(
            error: Throwable,
            endpoint: String,
            networks: () -> List<LocalNetwork> = LocalNetwork::ofDevice,
        ): SyncFailure = when (error) {
            is BackendSyncException.BackendSyncAuthFailedException -> AuthRejected
            is BackendNetworkException -> classifyNetwork(endpoint, networks)
            else -> Other(error.message?.takeIf { it.isNotBlank() } ?: error.toString())
        }

        private fun classifyNetwork(endpoint: String, networks: () -> List<LocalNetwork>): SyncFailure {
            val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            val host = uri?.host ?: return ServerUnreachable(endpoint.trim())
            val server = if (uri.port > 0) "$host:${uri.port}" else host
            val local = runCatching { networks() }.getOrDefault(emptyList())
            if (local.isEmpty()) return Offline
            val serverIp = parseIpv4(host) ?: return ServerUnreachable(server)
            if (serverIp.isLoopback() || !serverIp.isPrivate()) return ServerUnreachable(server)
            return if (local.any { it.contains(serverIp) }) NoAnswerOnNetwork(server) else NotOnServerNetwork(host)
        }

        /** Parses a dotted-quad literal without DNS; null for hostnames. */
        internal fun parseIpv4(host: String): Int? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            var value = 0
            for (part in parts) {
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 && part.length in 1..3 } ?: return null
                value = (value shl 8) or octet
            }
            return value
        }

        private fun Int.octet(i: Int): Int = (this ushr (24 - 8 * i)) and 0xFF

        private fun Int.isLoopback(): Boolean = octet(0) == 127

        /** RFC 1918 ranges: 10/8, 172.16/12, 192.168/16. */
        private fun Int.isPrivate(): Boolean =
            octet(0) == 10 ||
                (octet(0) == 172 && octet(1) in 16..31) ||
                (octet(0) == 192 && octet(1) == 168)
    }
}

/** One IPv4 network the phone is on: its [address] and the subnet's [prefixLength]. */
data class LocalNetwork(val address: Int, val prefixLength: Int) {

    /** True when [ip] is on this subnet. */
    fun contains(ip: Int): Boolean {
        val bits = prefixLength.coerceIn(0, 32)
        val mask = if (bits == 0) 0 else -1 shl (32 - bits)
        return (address and mask) == (ip and mask)
    }

    companion object {
        /** The device's non-loopback IPv4 networks on interfaces that are up. */
        fun ofDevice(): List<LocalNetwork> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { ia ->
                    val ip = ia.address as? Inet4Address ?: return@mapNotNull null
                    val b = ip.address
                    val value = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
                    LocalNetwork(value, ia.networkPrefixLength.toInt())
                }
    }
}
