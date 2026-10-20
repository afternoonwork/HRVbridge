package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntervalsOAuthTest {
    @Test
    fun buildsAuthoriseUrl() {
        val url = IntervalsOAuth.authorizeUrl(
            clientId = "123",
            redirectUri = "hrvbridge://oauth",
            state = "s t",
            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        )
        assertEquals(
            "https://intervals.icu/oauth/authorize?client_id=123&redirect_uri=hrvbridge%3A%2F%2Foauth" +
                "&scope=WELLNESS%3AWRITE&state=s%20t" +
                "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256",
            url,
        )
    }

    @Test
    fun buildsTokenRequestFormWithoutSecret() {
        assertEquals(
            "client_id=123&code=ab%2Bc&code_verifier=v-._~",
            IntervalsOAuth.tokenRequestBody(clientId = "123", code = "ab+c", codeVerifier = "v-._~"),
        )
        assertEquals("https://intervals.icu/api/oauth/token", IntervalsOAuth.TOKEN_URL)
    }

    @Test
    fun parsesTokenResponse() {
        val t = IntervalsOAuth.parseTokenResponse(
            """{"token_type":"Bearer","access_token":"abc","scope":"WELLNESS:WRITE","athlete":{"id":"i12345","name":"Paul"}}""",
        )
        assertEquals(TokenResponse("abc", "WELLNESS:WRITE", "i12345", "Paul"), t)
        assertTrue(t.canWriteWellness)
    }

    @Test
    fun detectsMissingWellnessScope() {
        // Users can deselect scopes on the consent screen.
        assertFalse(IntervalsOAuth.parseTokenResponse("""{"access_token":"a","scope":"ACTIVITY:READ"}""").canWriteWellness)
        assertFalse(IntervalsOAuth.parseTokenResponse("""{"access_token":"a"}""").canWriteWellness)
        // Scope list separator is not documented; accept comma or whitespace.
        assertTrue(IntervalsOAuth.parseTokenResponse("""{"access_token":"a","scope":"ACTIVITY:READ,WELLNESS:WRITE"}""").canWriteWellness)
        assertTrue(IntervalsOAuth.parseTokenResponse("""{"access_token":"a","scope":"ACTIVITY:READ WELLNESS:WRITE"}""").canWriteWellness)
        assertFalse(IntervalsOAuth.parseTokenResponse("""{"access_token":"a","scope":"WELLNESS:WRITEX"}""").canWriteWellness)
    }

    @Test
    fun acceptsNumericAthleteId() {
        assertEquals("42", IntervalsOAuth.parseTokenResponse("""{"access_token":"a","athlete":{"id":42}}""").athleteId)
    }

    @Test
    fun rejectsResponseWithoutToken() {
        assertFailsWith<IllegalArgumentException> { IntervalsOAuth.parseTokenResponse("""{"error":"invalid_grant"}""") }
        assertFailsWith<IllegalArgumentException> { IntervalsOAuth.parseTokenResponse("[]") }
    }

    @Test
    fun generatesUrlSafeState() {
        val s = IntervalsOAuth.generateState()
        assertTrue(s.length >= 22 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }, s)
    }
}
