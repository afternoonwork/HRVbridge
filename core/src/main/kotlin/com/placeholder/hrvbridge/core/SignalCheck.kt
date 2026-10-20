package com.placeholder.hrvbridge.core

import kotlin.math.abs

enum class SignalStatus {
    /** No notifications received yet. */
    NO_DATA,
    /** Latest packet reports poor skin contact: moisten electrodes, do not start the clock. */
    POOR_CONTACT,
    /** "This sensor does not send beat-to-beat data." */
    NO_RR,
    /** Mean RR does not match the reported heart rate. */
    IMPLAUSIBLE,
    OK,
}

/** Evaluates the notifications from the ~10 s connection check before a measurement. */
object SignalCheck {
    fun evaluate(measurements: List<HeartRateMeasurement>, tolerance: Double = 0.10): SignalStatus {
        if (measurements.isEmpty()) return SignalStatus.NO_DATA
        if (measurements.last().poorContact) return SignalStatus.POOR_CONTACT
        val rr = measurements.flatMap { it.rrIntervalsMs }
        if (rr.isEmpty()) return SignalStatus.NO_RR
        val hrs = measurements.map { it.heartRate }.filter { it > 0 }
        if (hrs.isEmpty()) return SignalStatus.IMPLAUSIBLE
        val expectedRr = 60000.0 / hrs.average()
        val meanRr = rr.average()
        return if (abs(meanRr - expectedRr) / expectedRr <= tolerance) SignalStatus.OK else SignalStatus.IMPLAUSIBLE
    }
}
