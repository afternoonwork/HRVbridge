package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogTest {
    @Test
    fun recordsLinesWithTimestamps() {
        var now = 0L
        val log = DebugLog(clockMs = { now })
        log.add("BLE", "connected")
        now = 1_234
        log.add("RX", "16 3C")
        assertEquals("[0.000 s] BLE connected\n[1.234 s] RX 16 3C", log.text())
    }

    @Test
    fun dropsOldestBeyondCapacityAndSaysSo() {
        val log = DebugLog(capacity = 3, clockMs = { 0 })
        repeat(5) { log.add("X", "line $it") }
        val text = log.text()
        assertTrue(text.startsWith("(2 older lines dropped)\n"), text)
        assertFalse(text.contains("line 1"))
        assertTrue(text.contains("line 4"))
    }

    @Test
    fun redactsRegisteredSecretsEvenIfLoggedByMistake() {
        val log = DebugLog(clockMs = { 0 })
        log.addSecret("sekr3t-api-key")
        log.add("HTTP", "Authorization: Basic sekr3t-api-key")
        log.add("HTTP", "token=sekr3t-api-key&x")
        val text = log.text()
        assertFalse(text.contains("sekr3t"), text)
        assertTrue(text.contains("[redacted]"))
    }

    @Test
    fun redactsBase64OfApiKeyCredential() {
        // A Basic header contains base64("API_KEY:key"), not the key itself.
        val log = DebugLog(clockMs = { 0 })
        val c = Credential.ApiKey("abc123")
        log.addSecrets(c)
        log.add("HTTP", Intervals.basicAuth("abc123"))
        assertFalse(log.text().contains("QVBJX0tFWTphYmMxMjM="), log.text())
    }

    @Test
    fun formatsPacketsAsHexWithParsedSummary() {
        val bytes = HeartRatePacketEncoder.encode(60, listOf(1024))
        assertEquals(
            "16 3C 00 04 -> HR 60, contact ok, RR [1000.0]",
            DebugLog.describePacket(bytes, HeartRateParser.parse(bytes)),
        )
        assertEquals("10 -> malformed", DebugLog.describePacket(byteArrayOf(0x10), null))
        val poor = HeartRatePacketEncoder.encode(60, emptyList(), contactDetected = false, rrPresent = false)
        assertEquals("04 3C -> HR 60, poor contact, no RR field", DebugLog.describePacket(poor, HeartRateParser.parse(poor)))
    }

    @Test
    fun clearEmptiesLogButKeepsSecrets() {
        val log = DebugLog(clockMs = { 0 })
        log.addSecret("k3y")
        log.add("A", "x")
        log.clear()
        assertEquals("", log.text())
        log.add("A", "k3y")
        assertFalse(log.text().contains("k3y"))
    }
}
