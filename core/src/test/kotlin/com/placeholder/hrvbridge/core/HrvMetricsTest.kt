package com.placeholder.hrvbridge.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrvMetricsTest {
    private fun assertClose(expected: Double, actual: Double, message: String) =
        assertTrue(abs(expected - actual) < 1e-9, "$message: expected $expected, got $actual")

    @Test
    fun matchesSharedTestVectors() {
        for (s in TestVectors.series) {
            val name = s["name"] as String
            @Suppress("UNCHECKED_CAST")
            val e = (s["expected"] as Map<String, Any?>)["metrics"] as Map<String, Any?>?
            val r = HrvMetrics.compute(ArtefactFilter.filter(TestVectors.doubles(s["rrMs"])))
            if (e == null) {
                assertNull(r, name)
                continue
            }
            assertNotNull(r, name)
            assertClose((e["meanNnMs"] as Number).toDouble(), r.meanNnMs, "$name meanNn")
            assertClose((e["rmssdMs"] as Number).toDouble(), r.rmssdMs, "$name rmssd")
            assertClose((e["sdnnMs"] as Number).toDouble(), r.sdnnMs, "$name sdnn")
            assertEquals((e["restingHr"] as Number).toInt(), r.restingHr, name)
            assertEquals((e["rmssdRounded"] as Number).toDouble(), r.rmssdRounded, name)
            assertEquals((e["sdnnRounded"] as Number).toDouble(), r.sdnnRounded, name)
            assertEquals((e["acceptedBeats"] as Number).toInt(), r.acceptedBeats, name)
            assertEquals((e["removedBeats"] as Number).toInt(), r.removedBeats, name)
            assertEquals((e["validPairs"] as Number).toInt(), r.validPairs, name)
            assertEquals(e["reliable"], r.reliable, name)
        }
    }

    @Test
    fun handComputedExample() {
        // NN 800, 850, 820, 880: diffs 50, -30, 60 -> rMSSD sqrt((2500+900+3600)/3) = 48.30...
        // mean 837.5 -> RHR round(71.64) = 72; SDNN sqrt(3675/3) = 35.0
        val r = HrvMetrics.compute(ArtefactFilter.filter(listOf(800.0, 850.0, 820.0, 880.0)))!!
        assertEquals(72, r.restingHr)
        assertEquals(48.3, r.rmssdRounded)
        assertEquals(35.0, r.sdnnRounded)
        assertFalse(r.reliable) // fewer than 40 beats
    }

    @Test
    fun rmssdSkipsPairsAcrossRejectedBeat() {
        // 1000, 1010, [250 rejected], 1020, 1030 -> valid pairs (1000,1010) and (1020,1030) only.
        val r = HrvMetrics.compute(ArtefactFilter.filter(listOf(1000.0, 1010.0, 250.0, 1020.0, 1030.0)))!!
        assertEquals(2, r.validPairs)
        assertEquals(10.0, r.rmssdMs)
    }

    @Test
    fun noResultWithoutValidPair() {
        assertNull(HrvMetrics.compute(ArtefactFilter.filter(listOf(1000.0, 250.0, 1000.0))))
    }

    @Test
    fun qualityGateIsConfigurable() {
        val series = ArtefactFilter.filter(List(10) { 1000.0 })
        assertFalse(HrvMetrics.compute(series)!!.reliable)
        assertTrue(HrvMetrics.compute(series, QualityGate(maxRemovedFraction = 0.05, minValidBeats = 10))!!.reliable)
    }

    @Test
    fun roundingMatchesSharedTestVectors() {
        @Suppress("UNCHECKED_CAST")
        val cases = TestVectors.root["rounding"] as List<Map<String, Any?>>
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val x = (c["value"] as Number).toDouble()
            val d = (c["decimals"] as Number).toInt()
            assertEquals((c["expected"] as Number).toDouble(), Rounding.halfUp(x, d), "$x to $d decimals")
        }
    }

    @Test
    fun roundsDecimalTiesUpDespiteBinaryRepresentation() {
        // 1.15 is stored as 1.1499999999999999...; plain BigDecimal(1.15) would round down.
        assertEquals(1.2, HrvMetrics.round1(1.15))
        assertEquals(68.5, HrvMetrics.round1(68.45))
        assertEquals(73, Rounding.halfUpToInt(72.5))
    }

    @Test
    fun roundingRejectsNegativeAndNaN() {
        assertFailsWith<IllegalArgumentException> { Rounding.halfUp(-0.1, 1) }
        assertFailsWith<IllegalArgumentException> { Rounding.halfUp(Double.NaN, 1) }
    }
}
