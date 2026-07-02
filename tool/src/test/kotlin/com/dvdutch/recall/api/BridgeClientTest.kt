package com.dvdutch.recall.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeClientTest {

    private val baseUrl = "https://bridge.example"
    private val token = "secret-token"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture: $name"
        }.bufferedReader().use { it.readText() }

    /** MockEngine that always returns a versioned JSON response of [body]. */
    private fun jsonEngine(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        bridgeApi: String? = "1",
        record: MutableList<HttpRequestData>? = null,
    ): MockEngine = MockEngine { request ->
        record?.add(request)
        val headers = if (bridgeApi != null) {
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                "X-Bridge-Api" to listOf(bridgeApi),
            )
        } else {
            headersOf(HttpHeaders.ContentType, "application/json")
        }
        respond(content = body, status = status, headers = headers)
    }

    private fun client(engine: MockEngine): BridgeClient =
        BridgeClient(baseUrl = baseUrl, token = token, engine = engine)

    // (a) auth header present on a decks() call
    @Test
    fun decksSendsBearerAuthHeader() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = jsonEngine(fixture("decks.json"), record = requests)
        client(engine).decks()

        val auth = requests.single().headers[HttpHeaders.Authorization]
        assertEquals("Bearer $token", auth)
    }

    // (b) missing X-Bridge-Api => VersionSkew(null)
    @Test
    fun missingVersionHeaderThrowsVersionSkew() = runBlocking {
        val engine = jsonEngine(fixture("decks.json"), bridgeApi = null)
        val ex = assertFailsWith<BridgeError.VersionSkew> { client(engine).decks() }
        assertEquals(null, ex.got)
    }

    // (b) "2" X-Bridge-Api => VersionSkew("2")
    @Test
    fun wrongVersionHeaderThrowsVersionSkew() = runBlocking {
        val engine = jsonEngine(fixture("decks.json"), bridgeApi = "2")
        val ex = assertFailsWith<BridgeError.VersionSkew> { client(engine).decks() }
        assertEquals("2", ex.got)
    }

    // (c) 401 => Unauthorized
    @Test
    fun unauthorizedThrowsUnauthorized() = runBlocking {
        val engine = jsonEngine(fixture("error-401.json"), status = HttpStatusCode.Unauthorized)
        assertFailsWith<BridgeError.Unauthorized> { client(engine).decks() }
        Unit
    }

    // (d) 503 needs_attention => NeedsAttention
    @Test
    fun needsAttention503ThrowsNeedsAttention() = runBlocking {
        val body = """{"detail":{"error":"needs_attention","message":"full sync required"}}"""
        val engine = jsonEngine(body, status = HttpStatusCode.ServiceUnavailable)
        assertFailsWith<BridgeError.NeedsAttention> { client(engine).studyStart(1L) }
        Unit
    }

    // A non-needs_attention 503 (or other non-2xx) maps to Server(detail)
    @Test
    fun otherServerErrorThrowsServerWithDetail() = runBlocking {
        val body = """{"detail":"collection is busy"}"""
        val engine = jsonEngine(body, status = HttpStatusCode.ServiceUnavailable)
        val ex = assertFailsWith<BridgeError.Server> { client(engine).decks() }
        assertTrue(ex.message.contains("collection is busy"), "message was: ${ex.message}")
    }

    // (e) connect exception => Unreachable
    @Test
    fun connectFailureThrowsUnreachable() = runBlocking {
        val engine = MockEngine { throw IOException("connection refused") }
        assertFailsWith<BridgeError.Unreachable> { client(engine).decks() }
        Unit
    }

    // (f) happy-path queue() parses the queue.json fixture bytes served by the mock
    @Test
    fun queueParsesFixture() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = jsonEngine(fixture("queue.json"), record = requests)
        val response = client(engine).queue(limit = 20)

        assertEquals(3, response.cards.size)
        assertEquals(19, response.counts.new)
        assertEquals(
            "20",
            requests.single().url.parameters["limit"],
            "expected limit=20 query param, url was ${requests.single().url}",
        )
    }

    // (g) answer() posts a {"answers":[...]} envelope and parses results
    @Test
    fun answerPostsEnvelopeAndParsesResults() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = jsonEngine(fixture("answer-duplicate.json"), record = requests)
        val answers = listOf(
            AnswerIn(
                uuid = "demo-answer-0001",
                cardId = 1781932102064,
                rating = "good",
                states = "CgY=",
                msTaken = 1200,
                answeredAt = 1_700_000_000_000,
            ),
        )
        val results = client(engine).answer(answers)

        assertEquals(1, results.size)
        assertEquals("demo-answer-0001", results.first().uuid)
        assertEquals("duplicate", results.first().status)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        val sentBody = (request.body as TextContent).text
        assertTrue(sentBody.contains("\"answers\""), "body missing envelope: $sentBody")
        assertTrue(sentBody.contains("demo-answer-0001"), "body missing answer: $sentBody")
    }
}
