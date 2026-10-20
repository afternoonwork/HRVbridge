package com.placeholder.hrvbridge.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntervalsClientTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val seen = ArrayList<Seen>()
    private var status = 200
    private var response = "{}"
    private val logLines = ArrayList<String>()
    private lateinit var client: IntervalsClient

    private val apiKey = Credential.ApiKey("abc123")
    private val values = WellnessValues(LocalDate.of(2026, 10, 20), 52, 68.4, 61.2)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            seen.add(Seen(ex.requestMethod, ex.requestURI.toString(), headers, ex.requestBody.readBytes().decodeToString()))
            val bytes = response.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        client = IntervalsClient(baseUrl = "http://127.0.0.1:${server.address.port}", log = { logLines.add(it) })
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun putsWellnessForLocalDate() {
        val r = client.putWellness(apiKey, values)
        assertIs<ApiResult.Success>(r)
        val s = seen.single()
        assertEquals("PUT", s.method)
        assertEquals("/api/v1/athlete/0/wellness/2026-10-20", s.path)
        assertEquals("Basic QVBJX0tFWTphYmMxMjM=", s.headers["authorization"])
        assertTrue(s.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("""{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2}""", s.body)
    }

    @Test
    fun bulkPutSendsArrayWithIds() {
        client.putWellnessBulk(Credential.OAuth("tok", null, null), listOf(values))
        val s = seen.single()
        assertEquals("/api/v1/athlete/0/wellness-bulk", s.path)
        assertEquals("Bearer tok", s.headers["authorization"])
        assertEquals("""[{"id":"2026-10-20","restingHR":52,"hrv":68.4,"hrvSDNN":61.2}]""", s.body)
    }

    @Test
    fun unauthorisedAndForbiddenMeanReconnect() {
        status = 401
        assertIs<ApiResult.Unauthorised>(client.putWellness(apiKey, values))
        status = 403
        assertIs<ApiResult.Unauthorised>(client.putWellness(apiKey, values))
    }

    @Test
    fun otherErrorsKeepCodeAndBody() {
        status = 500
        response = "boom"
        assertEquals(ApiResult.HttpError(500, "boom"), client.putWellness(apiKey, values))
    }

    @Test
    fun networkFailureIsReported() {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens
        val offline = IntervalsClient(baseUrl = "http://127.0.0.1:$port", timeoutMs = 2_000)
        assertIs<ApiResult.NetworkError>(offline.putWellness(apiKey, values))
    }

    @Test
    fun exchangesCodeForTokenWithoutSecret() {
        response = """{"access_token":"tok","scope":"ACTIVITY:WRITE,WELLNESS:WRITE","athlete":{"id":"i9","name":"Paul"}}"""
        val r = client.exchangeToken(clientId = "123", code = "c+d", codeVerifier = "v".repeat(43))
        val ok = assertIs<TokenExchange.Success>(r)
        assertEquals(TokenResponse("tok", "ACTIVITY:WRITE,WELLNESS:WRITE", "i9", "Paul"), ok.token)
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals("/api/oauth/token", s.path)
        assertEquals("application/x-www-form-urlencoded", s.headers["content-type"])
        assertEquals("client_id=123&code=c%2Bd&code_verifier=" + "v".repeat(43), s.body)
        assertFalse(s.headers.containsKey("authorization"))
    }

    @Test
    fun tokenExchangeFailures() {
        status = 400
        response = """{"error":"invalid_grant"}"""
        assertEquals(TokenExchange.Failure("HTTP 400: {\"error\":\"invalid_grant\"}"), client.exchangeToken("1", "c", "v".repeat(43)))
        status = 200
        response = "not json"
        assertIs<TokenExchange.Failure>(client.exchangeToken("1", "c", "v".repeat(43)))
    }

    @Test
    fun disconnectSendsDelete() {
        client.disconnect(Credential.OAuth("tok", null, null))
        val s = seen.single()
        assertEquals("DELETE", s.method)
        assertEquals("/api/v1/disconnect-app", s.path)
        assertEquals("Bearer tok", s.headers["authorization"])
    }

    @Test
    fun logNeverContainsCredentials() {
        client.putWellness(apiKey, values)
        client.disconnect(Credential.OAuth("tok-secret", null, null))
        response = """{"access_token":"tok-new"}"""
        client.exchangeToken("1", "code-secret", "v".repeat(43))
        val text = logLines.joinToString("\n")
        assertTrue(text.contains("PUT") && text.contains("/wellness/2026-10-20") && text.contains("200"), text)
        for (secret in listOf("abc123", "QVBJX0tFWTphYmMxMjM=", "tok-secret", "tok-new", "code-secret")) {
            assertFalse(text.contains(secret), "log leaked $secret:\n$text")
        }
    }
}
