package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatedStreamTest {
    @Test
    fun encoderRoundTripsThroughParser() {
        val bytes = HeartRatePacketEncoder.encode(heartRate = 60, rrRaw = listOf(1024, 512))
        assertContentEquals(byteArrayOf(0x16, 60, 0x00, 0x04, 0x00, 0x02), bytes)
        val m = HeartRateParser.parse(bytes)!!
        assertEquals(60, m.heartRate)
        assertEquals(listOf(1000.0, 500.0), m.rrIntervalsMs)
        assertTrue(m.contactDetected)
    }

    @Test
    fun encoderUsesUint16ForHighHeartRateAndOmitsRrWhenAbsent() {
        val m = HeartRateParser.parse(HeartRatePacketEncoder.encode(300, emptyList(), rrPresent = false))!!
        assertEquals(300, m.heartRate)
        assertFalse(m.rrPresent)
        val poor = HeartRateParser.parse(HeartRatePacketEncoder.encode(60, emptyList(), contactDetected = false))!!
        assertTrue(poor.poorContact)
    }

    @Test
    fun msToRawRoundsToNearestUnit() {
        assertEquals(1024, HeartRatePacketEncoder.msToRaw(1000.0))
        assertEquals(614, HeartRatePacketEncoder.msToRaw(600.0)) // 614.4
    }

    @Test
    fun groupsBeatsIntoOncePerSecondPackets() {
        // Beats end at 600, 1200, 1800, 2400 ms.
        val packets = SimulatedStream.fromRr(List(4) { 600.0 })
        assertEquals(listOf(1000L, 2000L, 3000L), packets.map { it.timeMs })
        val rr = packets.map { HeartRateParser.parse(it.bytes)!!.rrIntervalsMs.size }
        assertEquals(listOf(1, 2, 1), rr)
        assertEquals(100, HeartRateParser.parse(packets[1].bytes)!!.heartRate)
    }

    @Test
    fun packetWithoutBeatStillFlagsRrAndKeepsLastHeartRate() {
        // Beats end at 1500 and 3000: the packet at 1000 has no beat yet.
        val packets = SimulatedStream.fromRr(listOf(1500.0, 1500.0))
        val first = HeartRateParser.parse(packets[0].bytes)!!
        assertTrue(first.rrPresent)
        assertEquals(emptyList(), first.rrIntervalsMs)
        assertEquals(40, first.heartRate)
    }

    @Test
    fun reproducesExactlyRepresentableRrValues() {
        val rr = TestVectors.doubles(TestVectors.series.first { it["name"] == "clean 70 beats" }["rrMs"])
        val replayed = SimulatedStream.fromRr(rr).flatMap { HeartRateParser.parse(it.bytes)!!.rrIntervalsMs }
        assertEquals(rr, replayed)
    }

    @Test
    fun poorContactUntilMarksEarlyPackets() {
        val packets = SimulatedStream.fromRr(List(5) { 1000.0 }, poorContactUntilMs = 2000)
        assertEquals(listOf(true, true, false, false, false), packets.map { HeartRateParser.parse(it.bytes)!!.poorContact })
    }

    @Test
    fun heartRateOnlyStreamHasNoRr() {
        val packets = SimulatedStream.heartRateOnly(heartRate = 58, durationMs = 3000)
        assertEquals(3, packets.size)
        assertTrue(packets.all { HeartRateParser.parse(it.bytes)!!.let { m -> !m.rrPresent && m.heartRate == 58 } })
    }
}
