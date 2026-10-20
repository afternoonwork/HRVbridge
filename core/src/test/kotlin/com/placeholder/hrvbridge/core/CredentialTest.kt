package com.placeholder.hrvbridge.core

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CredentialTest {
    @Test
    fun authorizationHeaders() {
        assertEquals("Basic QVBJX0tFWTphYmMxMjM=", Credential.ApiKey("abc123").authorizationHeader)
        assertEquals("Bearer tok", Credential.OAuth("tok", "i1", "Paul").authorizationHeader)
    }

    @Test
    fun serialisesAndParses() {
        for (c in listOf(Credential.ApiKey("abc123"), Credential.OAuth("tok", "i1", "Paul"), Credential.OAuth("tok", null, null))) {
            assertEquals(c, Credential.fromJson(c.toJson()))
        }
    }

    @Test
    fun apiKeyIsTrimmedAndMustNotBeBlank() {
        assertEquals(Credential.ApiKey("abc"), Credential.ApiKey.fromUserInput("  abc \n"))
        assertFailsWith<IllegalArgumentException> { Credential.ApiKey.fromUserInput("   ") }
    }

    @Test
    fun toStringNeverShowsSecret() {
        assertEquals(false, Credential.ApiKey("abc123").toString().contains("abc123"))
        assertEquals(false, Credential.OAuth("tok123", "i1", "Paul").toString().contains("tok123"))
    }

    @Test
    fun secretBoxRoundTripsWithFreshIv() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plain = "secret".toByteArray()
        val a = SecretBox.seal(key, plain)
        val b = SecretBox.seal(key, plain)
        assertNotEquals(a.toList(), b.toList())
        assertContentEquals(plain, SecretBox.open(key, a))
    }

    @Test
    fun secretBoxDetectsTamperingAndWrongKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val box = SecretBox.seal(key, "secret".toByteArray())
        val tampered = box.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SecretBoxException> { SecretBox.open(key, tampered) }
        assertFailsWith<SecretBoxException> { SecretBox.open(other, box) }
        assertFailsWith<SecretBoxException> { SecretBox.open(key, byteArrayOf(9, 1, 2)) }
    }
}
