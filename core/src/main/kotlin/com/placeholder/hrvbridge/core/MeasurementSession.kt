package com.placeholder.hrvbridge.core

data class SessionConfig(
    /** Signal check before the clock starts: RR present, contact, plausibility. */
    val checkMs: Long = 10_000,
    /** Data discarded. User setting: 0, 30, 60 or 90 s. */
    val stabilisationMs: Long = 60_000,
    val measurementMs: Long = 60_000,
    val filter: ArtefactFilterConfig = ArtefactFilterConfig(),
    val gate: QualityGate = QualityGate(),
)

enum class FailReason { NO_DATA, NO_RR, IMPLAUSIBLE, DISCONNECTED }

sealed interface SessionState {
    /** [elapsedMs] restarts whenever contact is lost and regained. */
    data class Checking(val elapsedMs: Long, val poorContact: Boolean) : SessionState

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
 */
class MeasurementSession(private val config: SessionConfig = SessionConfig(), startMs: Long) {
    private enum class Phase { CHECKING, STABILISING, MEASURING }

    private var phase = Phase.CHECKING
    private var phaseStart = startMs
    private var now = startMs
    private var poorContact = false
    private val window = ArrayList<HeartRateMeasurement>()
    private val rr = ArrayList<Double>()
    private var terminal: SessionState? = null

    var malformedPackets = 0
        private set

    val state: SessionState
        get() = terminal ?: when (phase) {
            Phase.CHECKING -> SessionState.Checking(now - phaseStart, poorContact)
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
        when (phase) {
            Phase.CHECKING -> when {
                m.poorContact -> {
                    window.clear()
                    phaseStart = timeMs
                }
                wasPoor -> {
                    window.clear()
                    window.add(m)
                    phaseStart = timeMs
                }
                else -> window.add(m)
            }
            Phase.MEASURING -> rr.addAll(m.rrIntervalsMs)
            else -> Unit
        }
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
            when (phase) {
                Phase.CHECKING -> {
                    val end = phaseStart + config.checkMs
                    if (poorContact || now < end) return
                    when (SignalCheck.evaluate(window)) {
                        SignalStatus.OK -> enter(Phase.STABILISING, end)
                        SignalStatus.NO_DATA -> terminal = SessionState.Failed(FailReason.NO_DATA)
                        SignalStatus.NO_RR -> terminal = SessionState.Failed(FailReason.NO_RR)
                        SignalStatus.IMPLAUSIBLE -> terminal = SessionState.Failed(FailReason.IMPLAUSIBLE)
                        // Unreachable: poor contact is handled above.
                        SignalStatus.POOR_CONTACT -> return
                    }
                }
                Phase.STABILISING -> {
                    val end = phaseStart + config.stabilisationMs
                    if (now < end) return
                    enter(Phase.MEASURING, end)
                }
                Phase.MEASURING -> {
                    if (now < phaseStart + config.measurementMs) return
                    val series = ArtefactFilter.filter(rr.toList(), config.filter)
                    terminal = SessionState.Finished(series.rrMs, series, HrvMetrics.compute(series, config.gate))
                }
            }
        }
    }

    private fun enter(next: Phase, atMs: Long) {
        phase = next
        phaseStart = atMs
    }
}
