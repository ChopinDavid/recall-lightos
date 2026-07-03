package com.dvdutch.recall.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Envelope wrapping the outbound answer batch: `{"answers":[...]}`. */
@Serializable
private data class AnswerEnvelope(val answers: List<AnswerIn>)

/** Envelope wrapping the inbound answer results: `{"results":[...]}`. */
@Serializable
private data class AnswerResultsResponse(val results: List<AnswerResult>)

/** Body posted to `/v1/study/start`: `{"deck_id":...}`. */
@Serializable
private data class StudyStartRequest(val deck_id: Long)

/**
 * Thin, stateless HTTP client for the Anki bridge.
 *
 * Every request carries `Authorization: Bearer <token>`. Every response is run
 * through [enforce] which (1) verifies `X-Bridge-Api == "1"` BEFORE any body is
 * parsed and (2) maps every non-2xx status onto the [BridgeError] taxonomy.
 * Connect/IO failures surface as [BridgeError.Unreachable].
 *
 * Construction mirrors the SDK's weather example. Tests inject a `MockEngine`
 * via [engine]; production passes the default [OkHttp] engine.
 */
class BridgeClient(
    baseUrl: String,
    private val token: String,
    engine: HttpClientEngine = OkHttp.create(),
) : EngineApi {
    private val base = baseUrl.trimEnd('/')

    private val client = HttpClient(engine) {
        // We map statuses ourselves; don't let Ktor throw its own exceptions.
        expectSuccess = false
        install(ContentNegotiation) {
            json(BridgeJson)
        }
    }

    suspend fun status(): StatusResponse =
        request { get("$base/v1/status") { auth() } }.decode()

    suspend fun decks(): List<Deck> =
        request { get("$base/v1/decks") { auth() } }.decode<DecksResponse>().decks

    override suspend fun studyStart(deckId: Long): StudyStartResponse =
        request {
            post("$base/v1/study/start") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(StudyStartRequest(deckId))
            }
        }.decode()

    override suspend fun queue(limit: Int): QueueResponse =
        request {
            get("$base/v1/queue") {
                auth()
                parameter("limit", limit)
            }
        }.decode()

    override suspend fun answer(answers: List<AnswerIn>): List<AnswerResult> =
        request {
            post("$base/v1/answer") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(AnswerEnvelope(answers))
            }
        }.decode<AnswerResultsResponse>().results

    override suspend fun studyFinish(): SyncInfo =
        request { post("$base/v1/study/finish") { auth() } }.decode()

    suspend fun media(filename: String): ByteArray =
        request {
            get("$base/v1/media/${filename.encodeURLPathPart()}") { auth() }
        }.readRawBytes()

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /**
     * Executes [block], translating transport failures to [BridgeError.Unreachable],
     * then hands the response to [enforce] for version + status validation.
     */
    private suspend inline fun request(block: HttpClient.() -> HttpResponse): HttpResponse {
        val response = try {
            client.block()
        } catch (_: IOException) {
            throw BridgeError.Unreachable
        }
        return enforce(response)
    }

    /**
     * Verifies the API version header before anything else, then maps non-2xx
     * statuses. Returns the response untouched when it is a versioned 2xx.
     */
    private suspend fun enforce(response: HttpResponse): HttpResponse {
        val version = response.headers["X-Bridge-Api"]
        if (version != "1") throw BridgeError.VersionSkew(version)

        val status = response.status
        if (status.value in 200..299) return response

        when (status) {
            HttpStatusCode.Unauthorized -> throw BridgeError.Unauthorized
            HttpStatusCode.ServiceUnavailable ->
                if (response.isNeedsAttention()) throw BridgeError.NeedsAttention
        }
        throw BridgeError.Server(response.serverDetail())
    }

    /** Reads the JSON body and reports whether `detail.error == "needs_attention"`. */
    private suspend fun HttpResponse.isNeedsAttention(): Boolean {
        val detail = parseDetail() ?: return false
        val obj = detail as? JsonObject ?: return false
        return obj["error"]?.jsonPrimitive?.content == "needs_attention"
    }

    /**
     * Best-effort human-readable message for [BridgeError.Server]: the string
     * `detail`, or a nested `detail.message`, else the raw (truncated) body.
     */
    private suspend fun HttpResponse.serverDetail(): String {
        val body = bodyAsText()
        val detail = runCatching { BridgeJson.parseToJsonElement(body) }
            .getOrNull()
            ?.let { (it as? JsonObject)?.get("detail") }
        return when (detail) {
            null -> body.take(500).ifBlank { "HTTP ${status.value}" }
            is JsonObject -> detail["message"]?.jsonPrimitive?.content
                ?: detail.toString()
            else -> runCatching { detail.jsonPrimitive.content }.getOrNull()
                ?: detail.toString()
        }
    }

    private suspend fun HttpResponse.parseDetail() =
        runCatching { BridgeJson.parseToJsonElement(bodyAsText()).jsonObject["detail"] }
            .getOrNull()

    private suspend inline fun <reified T> HttpResponse.decode(): T =
        BridgeJson.decodeFromString(bodyAsText())

    fun close() = client.close()
}
