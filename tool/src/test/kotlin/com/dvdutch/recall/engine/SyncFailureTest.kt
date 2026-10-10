package com.dvdutch.recall.engine

import anki.backend.BackendError
import net.ankiweb.rsdroid.exceptions.BackendNetworkException
import net.ankiweb.rsdroid.exceptions.BackendSyncException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure classification of sync failures into what-to-do messages ([SyncFailure]). */
class SyncFailureTest {

    private val networkError = BackendNetworkException(BackendError.newBuilder().setMessage("network error").build())
    private val authError =
        BackendSyncException.BackendSyncAuthFailedException(BackendError.newBuilder().setMessage("auth").build())

    private fun net(ip: String, prefix: Int) = LocalNetwork(SyncFailure.parseIpv4(ip)!!, prefix)

    private fun classify(endpoint: String, vararg networks: LocalNetwork, error: Throwable = networkError) =
        SyncFailure.classify(error, endpoint) { networks.toList() }

    @Test
    fun `home server on a network the phone isn't on`() {
        val failure = classify("http://192.168.1.20:8080/", net("172.20.10.4", 28))
        assertEquals(SyncFailure.NotOnServerNetwork("192.168.1.20"), failure)
        assertTrue("isn't on the same network" in failure.reason)
    }

    @Test
    fun `home server on the phone's own network that doesn't answer`() {
        val failure = classify("http://192.168.1.20:8080/", net("192.168.1.37", 24))
        assertEquals(SyncFailure.NoAnswerOnNetwork("192.168.1.20:8080"), failure)
        assertTrue("on and awake" in failure.reason)
    }

    @Test
    fun `emulator host alias counts as the phone's own network`() {
        assertEquals(
            SyncFailure.NoAnswerOnNetwork("10.0.2.2:8080"),
            classify("http://10.0.2.2:8080/", net("10.0.2.15", 24)),
        )
    }

    @Test
    fun `any of several networks matching is enough`() {
        assertEquals(
            SyncFailure.NoAnswerOnNetwork("10.1.4.9:8080"),
            classify("http://10.1.4.9:8080/", net("100.64.0.2", 10), net("10.1.0.5", 16)),
        )
    }

    @Test
    fun `no network at all is offline`() {
        assertEquals(SyncFailure.Offline, classify("http://192.168.1.20:8080/"))
    }

    @Test
    fun `hostnames, public and loopback servers are just unreachable`() {
        val phone = net("192.168.1.37", 24)
        assertEquals(SyncFailure.ServerUnreachable("sync.example.com"), classify("https://sync.example.com/", phone))
        assertEquals(SyncFailure.ServerUnreachable("203.0.113.5:27701"), classify("http://203.0.113.5:27701/", phone))
        assertEquals(SyncFailure.ServerUnreachable("127.0.0.1:8080"), classify("http://127.0.0.1:8080/", phone))
        assertEquals(SyncFailure.ServerUnreachable("not a url"), classify("not a url", phone))
    }

    @Test
    fun `rejected credentials and other errors`() {
        assertEquals(SyncFailure.AuthRejected, classify("http://192.168.1.20:8080/", error = authError))
        assertEquals(SyncFailure.Other("boom"), classify("http://192.168.1.20:8080/", error = IllegalStateException("boom")))
    }

    @Test
    fun `sync detail promises saved reviews only when waiting will fix it`() {
        val away = SyncFailure.NotOnServerNetwork("192.168.1.20")
        assertTrue(away.syncDetail.startsWith("sync failed — this phone isn't on the same network"))
        assertTrue(away.syncDetail.endsWith("Your reviews are saved on this phone."))
        assertFalse("saved" in SyncFailure.AuthRejected.syncDetail)
        assertEquals("sync failed — boom", SyncFailure.Other("boom").syncDetail)
    }

    @Test
    fun `ipv4 parsing and subnet matching`() {
        assertEquals((192 shl 24) or (168 shl 16) or (1 shl 8) or 20, SyncFailure.parseIpv4("192.168.1.20"))
        assertNull(SyncFailure.parseIpv4("sync.example.com"))
        assertNull(SyncFailure.parseIpv4("1.2.3"))
        assertNull(SyncFailure.parseIpv4("256.1.1.1"))
        assertTrue(net("192.168.1.37", 24).contains(SyncFailure.parseIpv4("192.168.1.200")!!))
        assertFalse(net("192.168.1.37", 24).contains(SyncFailure.parseIpv4("192.168.2.1")!!))
        assertTrue(net("10.0.0.1", 8).contains(SyncFailure.parseIpv4("10.200.3.4")!!))
    }
}
