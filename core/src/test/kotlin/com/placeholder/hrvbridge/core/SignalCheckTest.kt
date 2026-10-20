package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalCheckTest {
    private fun m(hr: Int, rr: List<Double>, rrPresent: Boolean = true, supported: Boolean = true, detected: Boolean = true) =
        HeartRateMeasurement(hr, supported, detected, null, rrPresent, rr, false)

    @Test
    fun noData() = assertEquals(SignalStatus.NO_DATA, SignalCheck.evaluate(emptyList()))

    @Test
    fun poorContactWhenLatestPacketReportsIt() {
        val list = listOf(m(60, listOf(1000.0)), m(0, emptyList(), detected = false))
        assertEquals(SignalStatus.POOR_CONTACT, SignalCheck.evaluate(list))
    }

    @Test
    fun noRrWhenFlagNeverSet() {
        assertEquals(SignalStatus.NO_RR, SignalCheck.evaluate(List(10) { m(60, emptyList(), rrPresent = false) }))
    }

    @Test
    fun noRrWhenFlagSetButNoValuesArrive() {
        assertEquals(SignalStatus.NO_RR, SignalCheck.evaluate(List(10) { m(60, emptyList()) }))
    }

    @Test
    fun okWhenRrMatchesHeartRate() {
        assertEquals(SignalStatus.OK, SignalCheck.evaluate(List(10) { m(60, listOf(1000.0)) }))
        // 10% off is still accepted: expected 1000, mean 1100.
        assertEquals(SignalStatus.OK, SignalCheck.evaluate(List(10) { m(60, listOf(1100.0)) }))
    }

    @Test
    fun implausibleWhenRrDoesNotMatchHeartRate() {
        assertEquals(SignalStatus.IMPLAUSIBLE, SignalCheck.evaluate(List(10) { m(60, listOf(1101.0)) }))
        assertEquals(SignalStatus.IMPLAUSIBLE, SignalCheck.evaluate(List(10) { m(0, listOf(1000.0)) }))
    }
}
