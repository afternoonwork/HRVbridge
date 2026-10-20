package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateParserTest {
    @Test
    fun matchesSharedTestVectors() {
        for (p in TestVectors.packets) {
            val name = p["name"] as String
            val actual = HeartRateParser.parse(TestVectors.hex(p["hex"] as String))
            @Suppress("UNCHECKED_CAST")
            val e = p["expected"] as Map<String, Any?>?
            if (e == null) {
                assertNull(actual, name)
                continue
            }
            assertNotNull(actual, name)
            assertEquals((e["heartRate"] as Number).toInt(), actual.heartRate, name)
            assertEquals(e["contactSupported"], actual.contactSupported, name)
            assertEquals(e["contactDetected"], actual.contactDetected, name)
            assertEquals((e["energyExpended"] as Number?)?.toInt(), actual.energyExpended, name)
            assertEquals(e["rrPresent"], actual.rrPresent, name)
            assertEquals(TestVectors.doubles(e["rrMs"]), actual.rrIntervalsMs, name)
            assertEquals(e["droppedTrailingByte"], actual.droppedTrailingByte, name)
        }
    }

    @Test
    fun convertsRrWithoutRounding() {
        // raw 1023 -> 999.0234375 ms exactly
        val m = HeartRateParser.parse(byteArrayOf(0x10, 60, 0xFF.toByte(), 0x03))!!
        assertEquals(listOf(999.0234375), m.rrIntervalsMs)
    }

    @Test
    fun treatsHighBytesAsUnsigned() {
        // uint8 HR 200 and RR raw 0xFFFF must not go negative
        val m = HeartRateParser.parse(byteArrayOf(0x10, 0xC8.toByte(), 0xFF.toByte(), 0xFF.toByte()))!!
        assertEquals(200, m.heartRate)
        assertEquals(listOf(65535 * 1000.0 / 1024.0), m.rrIntervalsMs)
    }

    @Test
    fun poorContactOnlyWhenFeatureSupportedAndNotDetected() {
        assertTrue(HeartRateParser.parse(byteArrayOf(0x04, 60))!!.poorContact)
        assertFalse(HeartRateParser.parse(byteArrayOf(0x06, 60))!!.poorContact)
        // Feature not supported: bit1 meaningless, never report poor contact.
        assertFalse(HeartRateParser.parse(byteArrayOf(0x00, 60))!!.poorContact)
    }

    @Test
    fun parsesManyRrValues() {
        val bytes = byteArrayOf(0x10, 60) + ByteArray(8) { if (it % 2 == 0) 0x00 else 0x04 }
        assertEquals(List(4) { 1000.0 }, HeartRateParser.parse(bytes)!!.rrIntervalsMs)
    }
}
