package com.placeholder.hrvbridge.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

sealed interface ApiResult {
    data class Success(val code: Int, val body: String) : ApiResult

    /** 401/403: the token or key is no longer valid; ask the user to reconnect. */
    data class Unauthorised(val code: Int) : ApiResult

    data class HttpError(val code: Int, val body: String) : ApiResult

    data class NetworkError(val message: String) : ApiResult
}

/** Outcome of the key check after "Save key". */
enum class KeyCheck {
    VALID,

    /** 401/403: Intervals.icu did not accept the key. */
    REJECTED,

    /** Anything else (offline, server error): the key is kept, unchecked. */
    UNKNOWN;

    companion object {
        fun of(result: ApiResult): KeyCheck = when (result) {
            is ApiResult.Success -> VALID
            is ApiResult.Unauthorised -> REJECTED
            is ApiResult.HttpError, is ApiResult.NetworkError -> UNKNOWN
        }
    }
}

sealed interface TokenExchange {
    data class Success(val token: TokenResponse) : TokenExchange

    data class Failure(val message: String) : TokenExchange
}

/**
 * Blocking Intervals.icu client on HttpURLConnection (call off the main
 * thread). [log] receives method, path and status only: never headers, and
 * never bodies of the token endpoint.
 */
class IntervalsClient(
    private val baseUrl: String = Intervals.BASE_URL,
    private val timeoutMs: Int = 15_000,
    private val log: (String) -> Unit = {},
) {
    fun putWellness(credential: Credential, values: WellnessValues): ApiResult =
        send("PUT", Intervals.wellnessUrl(values.date, baseUrl), credential, JSON, Intervals.wellnessBody(values), logBody = true)

    fun putWellnessBulk(credential: Credential, values: List<WellnessValues>): ApiResult =
        send("PUT", Intervals.wellnessBulkUrl(baseUrl), credential, JSON, Intervals.bulkBody(values), logBody = true)

    /** Key check: reads one wellness record (local [date]), same access as the upload. */
    fun checkKey(credential: Credential, date: LocalDate): KeyCheck =
        KeyCheck.of(send("GET", Intervals.wellnessUrl(date, baseUrl), credential, null, null, logBody = false))

    /** Sick day calendar event for [date]; idempotent per date. */
    fun postSickEvent(credential: Credential, date: LocalDate): ApiResult =
        send("POST", Intervals.eventsUrl(baseUrl), credential, JSON, Intervals.sickEventBody(date), logBody = true)

    fun disconnect(credential: Credential): ApiResult =
        send("DELETE", Intervals.disconnectUrl(baseUrl), credential, null, null, logBody = false)

    fun exchangeToken(clientId: String, code: String, codeVerifier: String): TokenExchange {
        val body = IntervalsOAuth.tokenRequestBody(clientId, code, codeVerifier)
        return when (val r = send("POST", IntervalsOAuth.tokenUrl(baseUrl), null, FORM, body, logBody = false)) {
            is ApiResult.Success -> try {
                TokenExchange.Success(IntervalsOAuth.parseTokenResponse(r.body))
            } catch (e: IllegalArgumentException) {
                TokenExchange.Failure("unexpected token response")
            }
            is ApiResult.Unauthorised -> TokenExchange.Failure("HTTP ${r.code}")
            is ApiResult.HttpError -> TokenExchange.Failure("HTTP ${r.code}: ${r.body}")
            is ApiResult.NetworkError -> TokenExchange.Failure(r.message)
        }
    }

    private fun send(
        method: String,
        url: String,
        credential: Credential?,
        contentType: String?,
        body: String?,
        logBody: Boolean,
    ): ApiResult {
        val path = URL(url).path
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            credential?.let { conn.setRequestProperty("Authorization", it.authorizationHeader) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().decodeToString() } ?: ""
            log(buildString {
                append("$method $path -> $code")
                if (logBody && body != null) append(" request=").append(body)
                if (logBody && code >= 400) append(" response=").append(text.take(300))
            })
            when {
                code == 401 || code == 403 -> ApiResult.Unauthorised(code)
                code in 200..299 -> ApiResult.Success(code, text)
                else -> ApiResult.HttpError(code, text)
            }
        } catch (e: IOException) {
            log("$method $path -> network error: ${e.javaClass.simpleName}")
            ApiResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val FORM = "application/x-www-form-urlencoded"
    }
}
