package com.placeholder.hrvbridge.core

import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

data class TokenResponse(
    val accessToken: String,
    val scope: String?,
    val athleteId: String?,
    val athleteName: String?,
) {
    /** Users can deselect scopes on the consent screen, so check before storing the token. */
    val canWriteWellness: Boolean
        get() = scope?.split(',', ' ')?.any { it.trim() == IntervalsOAuth.SCOPE_WELLNESS_WRITE } == true
}

/** OAuth 2.0 authorisation code + PKCE as a public client (no client secret). */
object IntervalsOAuth {
    const val AUTHORIZE_URL = "https://intervals.icu/oauth/authorize"
    const val TOKEN_URL = "https://intervals.icu/api/oauth/token"
    const val SCOPE_WELLNESS_WRITE = "WELLNESS:WRITE"

    fun tokenUrl(base: String = Intervals.BASE_URL): String = "$base/api/oauth/token"

    fun authorizeUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        AUTHORIZE_URL + "?" + form(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to SCOPE_WELLNESS_WRITE,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )

    /** application/x-www-form-urlencoded body for POST [TOKEN_URL]; send within 2 minutes of the redirect. */
    fun tokenRequestBody(clientId: String, code: String, codeVerifier: String): String =
        form("client_id" to clientId, "code" to code, "code_verifier" to codeVerifier)

    fun parseTokenResponse(json: String): TokenResponse {
        val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("token response is not an object")
        val token = root["access_token"] as? String ?: throw IllegalArgumentException("no access_token in response")
        val athlete = root["athlete"] as? Map<*, *>
        return TokenResponse(
            accessToken = token,
            scope = root["scope"] as? String,
            athleteId = athlete?.get("id")?.toString(),
            athleteName = athlete?.get("name") as? String,
        )
    }

    /** Random URL-safe value for the `state` parameter (CSRF protection). */
    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    // URLEncoder does form encoding; switch to RFC 3986 style (%20 for space, keep ~).
    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
}
