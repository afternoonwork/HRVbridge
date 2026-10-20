package com.placeholder.hrvbridge.core

import kotlin.math.abs

data class ArtefactFilterConfig(
    val minRrMs: Double = 300.0,
    val maxRrMs: Double = 2000.0,
    /** Reject a beat differing more than this fraction from the reference median. */
    val maxRelativeDeviation: Double = 0.20,
    /** Number of previous accepted beats the reference median is taken over. */
    val medianWindow: Int = 5,
)

enum class RejectReason { OUT_OF_RANGE, RELATIVE }

class FilteredSeries(
    val rrMs: List<Double>,
    val accepted: List<Boolean>,
    /** Null where the beat was accepted. */
    val rejectReasons: List<RejectReason?>,
) {
    val acceptedCount: Int get() = accepted.count { it }
    val nn: List<Double> get() = rrMs.filterIndexed { i, _ -> accepted[i] }
}

object ArtefactFilter {
    /**
     * Range filter, then relative filter against the median of the previous
     * accepted beats. Before any beat is accepted the reference is the median
     * of the first [ArtefactFilterConfig.medianWindow] in-range beats, so an
     * artefact as the very first beat does not become the reference.
     */
    fun filter(rrMs: List<Double>, config: ArtefactFilterConfig = ArtefactFilterConfig()): FilteredSeries {
        fun inRange(x: Double) = x >= config.minRrMs && x <= config.maxRrMs
        val seedValues = rrMs.filter(::inRange).take(config.medianWindow)
        val seed = if (seedValues.isEmpty()) 0.0 else median(seedValues)
        val accepted = ArrayList<Boolean>(rrMs.size)
        val reasons = ArrayList<RejectReason?>(rrMs.size)
        val acceptedValues = ArrayList<Double>()
        for (x in rrMs) {
            val reason = when {
                !inRange(x) -> RejectReason.OUT_OF_RANGE
                else -> {
                    val ref = if (acceptedValues.isEmpty()) seed else median(acceptedValues.takeLast(config.medianWindow))
                    if (abs(x - ref) / ref > config.maxRelativeDeviation) RejectReason.RELATIVE else null
                }
            }
            accepted.add(reason == null)
            reasons.add(reason)
            if (reason == null) acceptedValues.add(x)
        }
        return FilteredSeries(rrMs, accepted, reasons)
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
