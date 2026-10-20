package com.placeholder.hrvbridge.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers (S256 only, as Intervals.icu requires). */
object Pkce {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val VALID = Regex("[A-Za-z0-9._~-]{43,128}")

    fun generateVerifier(random: SecureRandom = SecureRandom(), length: Int = 64): String {
        require(length in 43..128) { "verifier length must be 43..128" }
        return String(CharArray(length) { UNRESERVED[random.nextInt(UNRESERVED.length)] })
    }

    fun isValidVerifier(v: String): Boolean = VALID.matches(v)

    /** base64url(sha256(verifier)) without padding. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
