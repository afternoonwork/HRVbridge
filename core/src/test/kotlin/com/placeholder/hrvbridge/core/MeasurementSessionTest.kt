package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementSessionTest {
    private val fast = SessionConfig(checkMs = 10_000, stabilisationMs = 0, measurementMs = 60_000)

    private fun MeasurementSession.feed(packets: List<TimedPacket>, offsetMs: Long = 0) {
        for (p in packets) onPacket(p.bytes, p.timeMs + offsetMs)
    }

    private fun beat(rrMs: Double, contact: Boolean = true) =
        HeartRatePacketEncoder.encode(60, listOf(HeartRatePacketEncoder.msToRaw(rrMs)), contactDetected = contact)

    @Test
    fun runsThroughAllPhasesWithDefaultTiming() {
        val s = MeasurementSession(SessionConfig(), startMs = 0)
        assertEquals(SessionState.Checking(elapsedMs = 0, poorContact = false), s.state)
        val packets = SimulatedStream.fromRr(List(200) { 1000.0 })
        for (p in packets) {
            s.onPacket(p.bytes, p.timeMs)
            when (p.timeMs) {
                5_000L -> assertEquals(SessionState.Checking(5_000, false), s.state)
                40_000L -> assertEquals(SessionState.Stabilising(remainingMs = 30_000, poorContact = false), s.state)
                100_000L -> assertEquals(SessionState.Measuring(remainingMs = 30_000, beats = 31, poorContact = false), s.state)
            }
        }
        val done = assertIs<SessionState.Finished>(s.state)
        // Measuring window is [70 s, 130 s): packets at 70..129 s carry one 1000 ms beat each.
        assertEquals(60, done.rrMs.size)
        assertEquals(60, done.result!!.restingHr)
    }

    @Test
    fun tickAloneEndsPhases() {
        val s = MeasurementSession(fast, startMs = 1_000)
        s.feed(SimulatedStream.fromRr(List(9) { 1000.0 }), offsetMs = 1_000)
        s.onTick(11_000)
        assertEquals(SessionState.Measuring(60_000, 0, false), s.state)
        s.onTick(71_000)
        val done = assertIs<SessionState.Finished>(s.state)
        assertNull(done.result)
    }

    @Test
    fun poorContactRestartsTheCheckWindow() {
        val s = MeasurementSession(fast, startMs = 0)
        s.onPacket(beat(1000.0), 1_000)
        s.onPacket(beat(1000.0, contact = false), 8_000)
        assertEquals(SessionState.Checking(0, poorContact = true), s.state)
        s.onTick(12_000)
        assertEquals(SessionState.Checking(4_000, poorContact = true), s.state)
        // Good contact again: the 10 s check starts over from here.
        s.onPacket(beat(1000.0), 13_000)
        assertEquals(SessionState.Checking(0, poorContact = false), s.state)
        for (t in 14_000L..22_000L step 1_000) s.onPacket(beat(1000.0), t)
        assertIs<SessionState.Checking>(s.state)
        s.onPacket(beat(1000.0), 23_000)
        assertIs<SessionState.Measuring>(s.state)
    }

    @Test
    fun failsWhenSensorSendsNoRr() {
        val s = MeasurementSession(fast, startMs = 0)
        s.feed(SimulatedStream.heartRateOnly(60, 10_000))
        assertEquals(SessionState.Failed(FailReason.NO_RR), s.state)
    }

    @Test
    fun failsWithoutAnyData() {
        val s = MeasurementSession(fast, startMs = 0)
        s.onTick(10_000)
        assertEquals(SessionState.Failed(FailReason.NO_DATA), s.state)
    }

    @Test
    fun failsWhenRrDoesNotMatchHeartRate() {
        val s = MeasurementSession(fast, startMs = 0)
        for (t in 1_000L..10_000L step 1_000) s.onPacket(HeartRatePacketEncoder.encode(90, listOf(1024)), t)
        assertEquals(SessionState.Failed(FailReason.IMPLAUSIBLE), s.state)
    }

    @Test
    fun disconnectFailsUnlessFinished() {
        val s = MeasurementSession(fast, startMs = 0)
        s.onDisconnected()
        assertEquals(SessionState.Failed(FailReason.DISCONNECTED), s.state)
        // Later events are ignored once terminal.
        s.onPacket(beat(1000.0), 1_000)
        assertEquals(SessionState.Failed(FailReason.DISCONNECTED), s.state)
    }

    @Test
    fun ignoresMalformedPackets() {
        val s = MeasurementSession(fast, startMs = 0)
        s.onPacket(byteArrayOf(0x10), 1_000)
        assertEquals(1, s.malformedPackets)
        assertEquals(SessionState.Checking(1_000, false), s.state)
    }

    @Test
    fun pipelineReproducesVectorMetrics() {
        // Every series whose RR values are exact multiples of 1/1024 s survives
        // encode -> parse unchanged, so the session must reproduce its metrics.
        var checked = 0
        for (v in TestVectors.series) {
            val rr = TestVectors.doubles(v["rrMs"])
            if (rr.size < 2 || rr.any { HeartRatePacketEncoder.msToRaw(it) * 1000.0 / 1024.0 != it }) continue
            val total = rr.sum().toLong()
            val s = MeasurementSession(fast.copy(measurementMs = total + 1_000), startMs = 0)
            for (t in 500L..9_500L step 1_000) s.onPacket(beat(1000.0), t)
            var t = 10_000.0
            for (x in rr) {
                t += x
                s.onPacket(beat(x), t.toLong())
            }
            s.onTick(10_000 + total + 1_000)
            val done = assertIs<SessionState.Finished>(s.state, v["name"] as String)
            assertEquals(rr, done.rrMs, v["name"] as String)
            assertEquals(HrvMetrics.compute(ArtefactFilter.filter(rr)), done.result, v["name"] as String)
            checked++
        }
        assertTrue(checked >= 5, "only $checked vectors checked")
    }

    @Test
    fun finishedResultCarriesFilteredSeries() {
        val s = MeasurementSession(fast, startMs = 0)
        s.feed(SimulatedStream.fromRr(List(80) { 1000.0 }))
        val done = assertIs<SessionState.Finished>(s.state)
        assertNotNull(done.result)
        assertEquals(done.rrMs, done.series.rrMs)
        assertTrue(done.result.reliable)
    }
}
