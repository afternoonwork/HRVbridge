package com.placeholder.hrvbridge.core

data class SessionConfig(
    /** Signal check (RR present, contact, plausibility) over the first part of the first phase. */
    val checkMs: Long = 10_000,
    /** Data discarded. User setting: 0, 30, 60 or 90 s. */
    val stabilisationMs: Long = 60_000,
    val measurementMs: Long = 60_000,
    val filter: ArtefactFilterConfig = ArtefactFilterConfig(),
    val gate: QualityGate = QualityGate(),
)

enum class FailReason { NO_DATA, NO_RR, IMPLAUSIBLE, DISCONNECTED }

sealed interface SessionState {
    data class Stabilising(val remainingMs: Long, val poorContact: Boolean) : SessionState

    data class Measuring(val remainingMs: Long, val beats: Int, val poorContact: Boolean) : SessionState

    /** [result] is null when there was too little data to compute anything. */
    data class Finished(val rrMs: List<Double>, val series: FilteredSeries, val result: HrvResult?) : SessionState

    data class Failed(val reason: FailReason) : SessionState
}

/**
 * The measurement protocol as a state machine driven by packet and tick
 * timestamps from the caller's clock. Phase boundaries are exact
 * (start + duration), so results do not depend on tick frequency. A packet
 * belongs to the phase active at its arrival time; the measuring window is
 * [start, start + measurementMs).
 *
 * There is no separate check phase (decided 21 Oct 2026): the signal check
 * (RR present, plausible) runs on the first [SessionConfig.checkMs] of the
 * first phase, settling, or measuring when settling is 0 s. While contact is
 * poor before the check has passed, that phase's clock does not run.
 */
class MeasurementSession(private val config: SessionConfig = SessionConfig(), startMs: Long) {
    private enum class Phase { STABILISING, MEASURING }

    private var phase = if (config.stabilisationMs > 0) Phase.STABILISING else Phase.MEASURING
    private var phaseStart = startMs
    private var now = startMs
    private var poorContact = false
    /** The signal check has passed. */
    private var checked = false
    private val window = ArrayList<HeartRateMeasurement>()
    private val rr = ArrayList<Double>()
    private var terminal: SessionState? = null

    var malformedPackets = 0
        private set

    val state: SessionState
        get() = terminal ?: when (phase) {
            Phase.STABILISING -> SessionState.Stabilising(phaseStart + config.stabilisationMs - now, poorContact)
            Phase.MEASURING -> SessionState.Measuring(phaseStart + config.measurementMs - now, rr.size, poorContact)
        }

    fun onPacket(bytes: ByteArray, timeMs: Long) {
        if (terminal != null) return
        advance(timeMs)
        if (terminal != null) return
        val m = HeartRateParser.parse(bytes)
        if (m == null) {
            malformedPackets++
            return
        }
        val wasPoor = poorContact
        poorContact = m.poorContact
        if (!checked) {
            // Poor contact before the check: start the first phase over once contact is good.
            if (m.poorContact || wasPoor) {
                window.clear()
                rr.clear()
                phaseStart = timeMs
            }
            if (!m.poorContact) window.add(m)
        }
        if (phase == Phase.MEASURING && !(m.poorContact && !checked)) rr.addAll(m.rrIntervalsMs)
    }

    fun onTick(timeMs: Long) {
        if (terminal == null) advance(timeMs)
    }

    fun onDisconnected() {
        if (terminal == null) terminal = SessionState.Failed(FailReason.DISCONNECTED)
    }

    private fun advance(timeMs: Long) {
        now = maxOf(now, timeMs)
        while (terminal == null) {
            if (!checked) {
                if (poorContact) {
                    phaseStart = now // the clock waits for good contact
                    return
                }
                val end = phaseStart + minOf(config.checkMs, duration(phase))
                if (now < end) return
                when (SignalCheck.evaluate(window)) {
                    SignalStatus.OK -> checked = true
                    SignalStatus.NO_DATA -> terminal = SessionState.Failed(FailReason.NO_DATA)
                    SignalStatus.NO_RR -> terminal = SessionState.Failed(FailReason.NO_RR)
                    SignalStatus.IMPLAUSIBLE -> terminal = SessionState.Failed(FailReason.IMPLAUSIBLE)
                    // Unreachable: poor contact is handled above.
                    SignalStatus.POOR_CONTACT -> return
                }
                window.clear()
                continue
            }
            val end = phaseStart + duration(phase)
            if (now < end) return
            when (phase) {
                Phase.STABILISING -> {
                    phase = Phase.MEASURING
                    phaseStart = end
                }
                Phase.MEASURING -> {
                    val series = ArtefactFilter.filter(rr.toList(), config.filter)
                    terminal = SessionState.Finished(series.rrMs, series, HrvMetrics.compute(series, config.gate))
                }
            }
        }
    }

    private fun duration(p: Phase) = if (p == Phase.STABILISING) config.stabilisationMs else config.measurementMs
}
