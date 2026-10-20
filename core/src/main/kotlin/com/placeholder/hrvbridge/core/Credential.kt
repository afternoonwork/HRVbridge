package com.placeholder.hrvbridge.core

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How the app authenticates to Intervals.icu. toString never shows the secret. */
sealed class Credential {
    abstract val authorizationHeader: String
    internal abstract val secret: String

    abstract fun toJson(): String

    /** Development / advanced: personal API key, Basic auth with username API_KEY. */
    class ApiKey(val key: String) : Credential() {
        override val authorizationHeader get() = Intervals.basicAuth(key)
        override val secret get() = key

        override fun toJson() = Json.write(mapOf("type" to "apiKey", "key" to key))

        override fun equals(other: Any?) = other is ApiKey && other.key == key

        override fun hashCode() = key.hashCode()

        override fun toString() = "ApiKey(***)"

        companion object {
            fun fromUserInput(text: String): ApiKey {
                val k = text.trim()
                require(k.isNotEmpty()) { "API key is empty" }
                return ApiKey(k)
            }
        }
    }

    /** OAuth access token (no refresh tokens: 401/403 means reconnect). */
    class OAuth(val accessToken: String, val athleteId: String?, val athleteName: String?) : Credential() {
        override val authorizationHeader get() = Intervals.bearer(accessToken)
        override val secret get() = accessToken

        override fun toJson() = Json.write(
            mapOf("type" to "oauth", "accessToken" to accessToken, "athleteId" to athleteId, "athleteName" to athleteName),
        )

        override fun equals(other: Any?) =
            other is OAuth && other.accessToken == accessToken && other.athleteId == athleteId && other.athleteName == athleteName

        override fun hashCode() = accessToken.hashCode()

        override fun toString() = "OAuth(***, athleteId=$athleteId, athleteName=$athleteName)"
    }

    companion object {
        fun fromJson(text: String): Credential {
            val m = Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("not an object")
            return when (m["type"]) {
                "apiKey" -> ApiKey(m["key"] as String)
                "oauth" -> OAuth(m["accessToken"] as String, m["athleteId"] as String?, m["athleteName"] as String?)
                else -> throw IllegalArgumentException("unknown credential type")
            }
        }
    }
}

class SecretBoxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-GCM sealing for the stored credential. The cipher picks the IV (the
 * Android Keystore requires that). Format: version(1) | ivLength(1) | iv | ciphertext+tag.
 */
object SecretBox {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT: Byte = 1

    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(2 + iv.size + ct.size).put(FORMAT).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    fun open(key: SecretKey, box: ByteArray): ByteArray {
        if (box.size < 2 || box[0] != FORMAT) throw SecretBoxException("unknown format")
        val ivLen = box[1].toInt() and 0xFF
        if (box.size < 2 + ivLen + 16) throw SecretBoxException("truncated")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, box, 2, ivLen))
            return cipher.doFinal(box, 2 + ivLen, box.size - 2 - ivLen)
        } catch (e: GeneralSecurityException) {
            throw SecretBoxException("cannot decrypt", e)
        }
    }
}
