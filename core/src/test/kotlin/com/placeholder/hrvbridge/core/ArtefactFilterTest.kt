package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtefactFilterTest {
    @Test
    fun matchesSharedTestVectors() {
        for (s in TestVectors.series) {
            val name = s["name"] as String
            @Suppress("UNCHECKED_CAST")
            val e = s["expected"] as Map<String, Any?>
            val result = ArtefactFilter.filter(TestVectors.doubles(s["rrMs"]))
            assertEquals(e["accepted"], result.accepted, name)
            assertEquals(
                (e["rejectReasons"] as List<*>).map { it?.let { r -> RejectReason.valueOf(r as String) } },
                result.rejectReasons,
                name,
            )
        }
    }

    @Test
    fun rejectsCompensatoryPauseAfterPrematureBeat() {
        val rr = List(10) { 1000.0 } + listOf(600.0, 1400.0) + List(10) { 1000.0 }
        val r = ArtefactFilter.filter(rr)
        assertEquals(listOf(RejectReason.RELATIVE, RejectReason.RELATIVE), r.rejectReasons.subList(10, 12))
        assertEquals(20, r.acceptedCount)
    }

    @Test
    fun usesMedianOfPreviousFiveAcceptedOnly() {
        // Rejected beats must not enter the reference window.
        val rr = listOf(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1500.0, 1500.0, 1150.0)
        val r = ArtefactFilter.filter(rr)
        assertEquals(listOf(true, true, true, true, true, false, false, true), r.accepted)
    }

    @Test
    fun medianOfEvenWindowAveragesMiddleValues() {
        assertEquals(2.5, ArtefactFilter.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(3.0, ArtefactFilter.median(listOf(5.0, 1.0, 3.0)))
    }

    @Test
    fun respectsCustomConfig() {
        val cfg = ArtefactFilterConfig(minRrMs = 500.0, maxRrMs = 1500.0, maxRelativeDeviation = 0.5, medianWindow = 3)
        val r = ArtefactFilter.filter(listOf(1000.0, 1000.0, 1400.0, 450.0, 1600.0), cfg)
        assertEquals(listOf(true, true, true, false, false), r.accepted)
    }
}
