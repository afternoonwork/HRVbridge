package com.placeholder.hrvbridge.core

import kotlin.math.floor
import kotlin.math.sqrt

data class QualityGate(
    /** Unreliable if strictly more than this fraction of beats was removed. */
    val maxRemovedFraction: Double = 0.05,
    val minValidBeats: Int = 40,
)

data class HrvResult(
    val meanNnMs: Double,
    /** round(60000 / mean NN), bpm. */
    val restingHr: Int,
    val rmssdMs: Double,
    val sdnnMs: Double,
    val acceptedBeats: Int,
    val removedBeats: Int,
    val validPairs: Int,
    /** False: "Measurement unreliable, please repeat"; upload only on user override. */
    val reliable: Boolean,
) {
    val rmssdRounded: Double get() = HrvMetrics.round1(rmssdMs)
    val sdnnRounded: Double get() = HrvMetrics.round1(sdnnMs)
}

object HrvMetrics {
    /** Returns null when there are fewer than two accepted beats or no valid successive pair. */
    fun compute(series: FilteredSeries, gate: QualityGate = QualityGate()): HrvResult? {
        val rr = series.rrMs
        val ok = series.accepted
        var sumSq = 0.0
        var pairs = 0
        for (i in 0 until rr.size - 1) {
            if (ok[i] && ok[i + 1]) {
                val d = rr[i + 1] - rr[i]
                sumSq += d * d
                pairs++
            }
        }
        val nn = series.nn
        if (nn.size < 2 || pairs == 0) return null

        // Plain sequential sums so results match the reference generator exactly.
        var total = 0.0
        for (x in nn) total += x
        val mean = total / nn.size
        var ss = 0.0
        for (x in nn) ss += (x - mean) * (x - mean)

        val removed = rr.size - nn.size
        return HrvResult(
            meanNnMs = mean,
            restingHr = floor(60000.0 / mean + 0.5).toInt(),
            rmssdMs = sqrt(sumSq / pairs),
            sdnnMs = sqrt(ss / (nn.size - 1)),
            acceptedBeats = nn.size,
            removedBeats = removed,
            validPairs = pairs,
            reliable = removed.toDouble() / rr.size <= gate.maxRemovedFraction && nn.size >= gate.minValidBeats,
        )
    }

    /** Half-up rounding to one decimal place. */
    fun round1(x: Double): Double = floor(x * 10.0 + 0.5) / 10.0
}
