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
        // No separate check phase: settling starts at once, the check runs on its first 10 s.
        val s = MeasurementSession(SessionConfig(), startMs = 0)
        assertEquals(SessionState.Stabilising(remainingMs = 60_000, poorContact = false), s.state)
        val packets = SimulatedStream.fromRr(List(200) { 1000.0 })
        for (p in packets) {
            s.onPacket(p.bytes, p.timeMs)
            when (p.timeMs) {
                5_000L -> assertEquals(SessionState.Stabilising(55_000, false), s.state)
                40_000L -> assertEquals(SessionState.Stabilising(remainingMs = 20_000, poorContact = false), s.state)
                100_000L -> assertEquals(SessionState.Measuring(remainingMs = 20_000, beats = 41, poorContact = false), s.state)
            }
        }
        val done = assertIs<SessionState.Finished>(s.state)
        // Measuring window is [60 s, 120 s): packets at 60..119 s carry one 1000 ms beat each.
        assertEquals(60, done.rrMs.size)
        assertEquals(60, done.result!!.restingHr)
    }

    @Test
    fun tickAloneEndsPhases() {
        val s = MeasurementSession(fast, startMs = 1_000)
        s.feed(SimulatedStream.fromRr(List(9) { 1000.0 }), offsetMs = 1_000)
        // 0 s settling: the check runs on the first 10 s of the measurement itself.
        s.onTick(11_000)
        assertEquals(SessionState.Measuring(50_000, 9, false), s.state)
        s.onTick(61_000)
        val done = assertIs<SessionState.Finished>(s.state)
        // The 9 beats of the check window count; too few for a reliable result.
        assertEquals(9, done.result!!.acceptedBeats)
        assertTrue(!done.result!!.reliable)
    }

    @Test
    fun poorContactBeforeTheCheckHoldsTheClock() {
        val s = MeasurementSession(fast, startMs = 0)
        s.onPacket(beat(1000.0), 1_000)
        s.onPacket(beat(1000.0, contact = false), 8_000)
        assertEquals(SessionState.Measuring(60_000, 0, poorContact = true), s.state)
        s.onTick(12_000)
        assertEquals(SessionState.Measuring(60_000, 0, poorContact = true), s.state)
        // Good contact again: the phase (and its 10 s check) starts over from here.
        s.onPacket(beat(1000.0), 13_000)
        assertEquals(SessionState.Measuring(60_000, 1, poorContact = false), s.state)
        for (t in 14_000L..22_000L step 1_000) s.onPacket(beat(1000.0), t)
        s.onPacket(beat(1000.0), 23_000)
        assertEquals(SessionState.Measuring(50_000, 11, false), s.state)
    }

    @Test
    fun checkRunsOnTheFirstTenSecondsOfSettling() {
        val s = MeasurementSession(SessionConfig(stabilisationMs = 30_000), startMs = 0)
        for (t in 1_000L..9_000L step 1_000) s.onPacket(HeartRatePacketEncoder.encode(90, listOf(1024)), t)
        assertEquals(SessionState.Stabilising(21_000, false), s.state)
        s.onTick(10_000)
        assertEquals(SessionState.Failed(FailReason.IMPLAUSIBLE), s.state)
    }

    @Test
    fun poorContactAfterTheCheckOnlyFlags() {
        val s = MeasurementSession(SessionConfig(stabilisationMs = 30_000), startMs = 0)
        for (t in 1_000L..10_000L step 1_000) s.onPacket(beat(1000.0), t)
        s.onPacket(beat(1000.0, contact = false), 12_000)
        assertEquals(SessionState.Stabilising(18_000, poorContact = true), s.state)
        s.onTick(30_000)
        assertEquals(SessionState.Measuring(60_000, 0, poorContact = true), s.state)
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
        assertEquals(SessionState.Measuring(59_000, 0, false), s.state)
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
            // 10 s settling (holding the check), then the measurement from 10 s.
            val s = MeasurementSession(fast.copy(stabilisationMs = 10_000, measurementMs = total + 1_000), startMs = 0)
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
