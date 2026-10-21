package com.placeholder.hrvbridge.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Paul's real Polar H9 capture (iOS raw recorder) through parser, filter, metrics and the
 * measurement session. Expected values: tools/gen_test_vectors.py --recordings.
 */
class RecordingTest {
    private val name = "polar-h9-2026-10-21"

    @Suppress("UNCHECKED_CAST")
    private fun load(file: String) =
        Json.parse(RecordingTest::class.java.getResource("/recordings/$file")!!.readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val packets = (load("$name.json")["packets"] as List<Map<String, Any?>>)
        .map { TimedPacket((it["tMs"] as Number).toLong(), TestVectors.hex(it["hex"] as String)) }
    private val expected = load("$name.expected.json")

    private fun assertMetrics(e: Map<*, *>, r: HrvResult?) {
        assertNotNull(r)
        for ((k, v) in listOf("meanNnMs" to r.meanNnMs, "rmssdMs" to r.rmssdMs, "sdnnMs" to r.sdnnMs)) {
            assertTrue(abs((e[k] as Number).toDouble() - v) < 1e-9, "$k: ${e[k]} vs $v")
        }
        assertEquals((e["restingHr"] as Number).toInt(), r.restingHr)
        assertEquals((e["rmssdRounded"] as Number).toDouble(), r.rmssdRounded)
        assertEquals((e["sdnnRounded"] as Number).toDouble(), r.sdnnRounded)
        assertEquals((e["acceptedBeats"] as Number).toInt(), r.acceptedBeats)
        assertEquals((e["removedBeats"] as Number).toInt(), r.removedBeats)
        assertEquals((e["validPairs"] as Number).toInt(), r.validPairs)
        assertEquals(e["reliable"], r.reliable)
    }

    @Test
    fun wholeRecordingThroughParserFilterAndMetrics() {
        val parsed = packets.map { HeartRateParser.parse(it.bytes) }
        assertEquals((expected["parsedPackets"] as Number).toInt(), parsed.count { it != null })
        assertEquals((expected["packetsWithRr"] as Number).toInt(), parsed.count { !it?.rrIntervalsMs.isNullOrEmpty() })
        val rr = parsed.flatMap { it?.rrIntervalsMs ?: emptyList() }
        assertEquals(TestVectors.doubles(expected["rrMs"]), rr)
        val series = ArtefactFilter.filter(rr)
        assertEquals(expected["accepted"], series.accepted)
        assertEquals(
            (expected["rejectReasons"] as List<*>).map { it?.let { r -> RejectReason.valueOf(r as String) } },
            series.rejectReasons,
        )
        assertMetrics(expected["metrics"] as Map<*, *>, HrvMetrics.compute(series))
    }

    @Test
    fun measurementSessionOverTheRecording() {
        val e = expected["session"] as Map<*, *>
        val config = SessionConfig(
            checkMs = (e["checkMs"] as Number).toLong(),
            stabilisationMs = (e["stabilisationMs"] as Number).toLong(),
            measurementMs = (e["measurementMs"] as Number).toLong(),
        )
        val s = MeasurementSession(config, startMs = 0)
        for (p in packets) s.onPacket(p.bytes, p.timeMs)
        s.onTick(config.checkMs + config.stabilisationMs + config.measurementMs)
        val done = assertIs<SessionState.Finished>(s.state)
        assertEquals(TestVectors.doubles(e["rrMs"]), done.rrMs)
        assertEquals(e["accepted"], done.series.accepted)
        assertMetrics(e["metrics"] as Map<*, *>, done.result)
    }
}
