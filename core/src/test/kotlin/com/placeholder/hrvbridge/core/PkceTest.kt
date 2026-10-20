package com.placeholder.hrvbridge.core

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun generatedVerifierIsValid() {
        val v = Pkce.generateVerifier(SecureRandom())
        assertEquals(64, v.length)
        assertTrue(Pkce.isValidVerifier(v))
        assertNotEquals(v, Pkce.generateVerifier(SecureRandom()))
        assertEquals(43, Pkce.generateVerifier(length = 43).length)
        assertEquals(128, Pkce.generateVerifier(length = 128).length)
    }

    @Test
    fun rejectsOutOfSpecLengths() {
        assertFailsWith<IllegalArgumentException> { Pkce.generateVerifier(length = 42) }
        assertFailsWith<IllegalArgumentException> { Pkce.generateVerifier(length = 129) }
    }

    @Test
    fun validatesVerifierCharset() {
        assertTrue(Pkce.isValidVerifier("A".repeat(40) + "-._~"))
        assertFalse(Pkce.isValidVerifier("A".repeat(42)))
        assertFalse(Pkce.isValidVerifier("A".repeat(42) + "+"))
        assertFalse(Pkce.isValidVerifier("A".repeat(129)))
    }
}
