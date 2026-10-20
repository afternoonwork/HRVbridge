package com.placeholder.hrvbridge.core

/**
 * In-memory log for the "share debug log" button: BLE events, parsed
 * packets, results and HTTP status lines. Credentials must never be logged;
 * as a second line of defence every registered secret is redacted on export.
 * Thread-safe.
 */
class DebugLog(private val capacity: Int = 5_000, private val clockMs: () -> Long) {
    private val lines = ArrayDeque<String>()
    private val secrets = LinkedHashSet<String>()
    private var dropped = 0

    @Synchronized
    fun add(tag: String, message: String) {
        val t = clockMs()
        lines.addLast("[${t / 1000}.${(t % 1000).toString().padStart(3, '0')} s] $tag $message")
        while (lines.size > capacity) {
            lines.removeFirst()
            dropped++
        }
    }

    @Synchronized
    fun addSecret(secret: String) {
        if (secret.isNotBlank()) secrets.add(secret)
    }

    /** Registers the secret and the exact header value it produces. */
    fun addSecrets(credential: Credential) {
        addSecret(credential.secret)
        addSecret(credential.authorizationHeader.substringAfter(' '))
    }

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
    }

    @Synchronized
    fun text(): String {
        val body = buildString {
            if (dropped > 0) append("($dropped older lines dropped)\n")
            append(lines.joinToString("\n"))
        }
        // Longest first, so a secret containing another is fully replaced.
        return secrets.sortedByDescending { it.length }.fold(body) { acc, s -> acc.replace(s, "[redacted]") }
    }

    companion object {
        fun describePacket(bytes: ByteArray, m: HeartRateMeasurement?): String {
            val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            if (m == null) return "$hex -> malformed"
            val contact = when {
                !m.contactSupported -> "contact n/a"
                m.contactDetected -> "contact ok"
                else -> "poor contact"
            }
            val rr = if (m.rrPresent) "RR ${m.rrIntervalsMs}" else "no RR field"
            val trailing = if (m.droppedTrailingByte) ", dropped odd trailing byte" else ""
            return "$hex -> HR ${m.heartRate}, $contact, $rr$trailing"
        }
    }
}
