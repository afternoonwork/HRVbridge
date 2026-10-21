import Foundation

public enum SignalStatus: Equatable, Sendable {
    case noData, poorContact, noRR, implausible, ok
}

/// Evaluates the ~10 s connection check before a measurement. Plausibility: mean of
/// artefact-filtered RR within `tolerance` of 60000 / median reported HR.
public enum SignalCheck {
    public static func evaluate(_ measurements: [HeartRateMeasurement], tolerance: Double = 0.10) -> SignalStatus {
        guard let last = measurements.last else { return .noData }
        if last.poorContact { return .poorContact }
        let rr = measurements.flatMap(\.rrIntervalsMs)
        if rr.isEmpty { return .noRR }
        let hrs = measurements.map(\.heartRate).filter { $0 > 0 }
        let nn = ArtefactFilter.filter(rr).nn
        if hrs.isEmpty || nn.isEmpty { return .implausible }
        let expectedRr = 60000.0 / ArtefactFilter.median(hrs.map(Double.init))
        let meanRr = nn.reduce(0, +) / Double(nn.count)
        return abs(meanRr - expectedRr) / expectedRr <= tolerance ? .ok : .implausible
    }
}

public struct SessionConfig: Equatable, Sendable {
    /// Signal check (RR present, contact, plausibility) over the first part of the first phase.
    public var checkMs: Int64 = 10_000
    /// Data discarded. User setting: 0, 30, 60 or 90 s.
    public var stabilisationMs: Int64 = 60_000
    public var measurementMs: Int64 = 60_000
    public var filter = ArtefactFilterConfig()
    public var gate = QualityGate()

    public init(checkMs: Int64 = 10_000, stabilisationMs: Int64 = 60_000, measurementMs: Int64 = 60_000) {
        self.checkMs = checkMs
        self.stabilisationMs = stabilisationMs
        self.measurementMs = measurementMs
    }
}

public enum FailReason: Equatable, Sendable {
    case noData, noRR, implausible, disconnected
}

public enum SessionState: Equatable, Sendable {
    case stabilising(remainingMs: Int64, poorContact: Bool)
    case measuring(remainingMs: Int64, beats: Int, poorContact: Bool)
    /// `result` is nil when there was too little data to compute anything.
    case finished(rrMs: [Double], series: FilteredSeries, result: HrvResult?)
    case failed(FailReason)

    public var isActive: Bool {
        switch self {
        case .stabilising, .measuring: return true
        case .finished, .failed: return false
        }
    }
}

/// The measurement protocol as a state machine driven by caller timestamps (same as
/// Android's MeasurementSession). Phase boundaries are exact, so results do not depend
/// on tick frequency. The measuring window is [start, start + measurementMs).
///
/// No separate check phase (decided 21 Oct 2026): the signal check (RR present,
/// plausible) runs on the first `checkMs` of the first phase, settling, or measuring
/// when settling is 0 s. While contact is poor before the check has passed, that
/// phase's clock does not run.
public final class MeasurementSession {
    private enum Phase { case stabilising, measuring }

    private let config: SessionConfig
    private var phase: Phase
    private var phaseStart: Int64
    private var now: Int64
    private var poorContact = false
    /// The signal check has passed.
    private var checked = false
    private var window: [HeartRateMeasurement] = []
    private var rr: [Double] = []
    private var terminal: SessionState?
    public private(set) var malformedPackets = 0

    public init(config: SessionConfig = SessionConfig(), startMs: Int64) {
        self.config = config
        phase = config.stabilisationMs > 0 ? .stabilising : .measuring
        phaseStart = startMs
        now = startMs
    }

    public var state: SessionState {
        if let t = terminal { return t }
        switch phase {
        case .stabilising: return .stabilising(remainingMs: phaseStart + config.stabilisationMs - now, poorContact: poorContact)
        case .measuring: return .measuring(remainingMs: phaseStart + config.measurementMs - now, beats: rr.count, poorContact: poorContact)
        }
    }

    public func onPacket(_ bytes: [UInt8], timeMs: Int64) {
        guard terminal == nil else { return }
        advance(timeMs)
        guard terminal == nil else { return }
        guard let m = HeartRateParser.parse(bytes) else {
            malformedPackets += 1
            return
        }
        let wasPoor = poorContact
        poorContact = m.poorContact
        if !checked {
            // Poor contact before the check: start the first phase over once contact is good.
            if m.poorContact || wasPoor {
                window.removeAll()
                rr.removeAll()
                phaseStart = timeMs
            }
            if !m.poorContact { window.append(m) }
        }
        if phase == .measuring && !(m.poorContact && !checked) { rr.append(contentsOf: m.rrIntervalsMs) }
    }

    public func onTick(_ timeMs: Int64) {
        if terminal == nil { advance(timeMs) }
    }

    public func onDisconnected() {
        if terminal == nil { terminal = .failed(.disconnected) }
    }

    private func advance(_ timeMs: Int64) {
        now = max(now, timeMs)
        while terminal == nil {
            if !checked {
                if poorContact {
                    phaseStart = now // the clock waits for good contact
                    return
                }
                if now < phaseStart + min(config.checkMs, duration(phase)) { return }
                switch SignalCheck.evaluate(window) {
                case .ok: checked = true
                case .noData: terminal = .failed(.noData)
                case .noRR: terminal = .failed(.noRR)
                case .implausible: terminal = .failed(.implausible)
                case .poorContact: return // unreachable: handled above
                }
                window.removeAll()
                continue
            }
            let end = phaseStart + duration(phase)
            if now < end { return }
            switch phase {
            case .stabilising:
                phase = .measuring
                phaseStart = end
            case .measuring:
                let series = ArtefactFilter.filter(rr, config: config.filter)
                terminal = .finished(rrMs: series.rrMs, series: series, result: HrvMetrics.compute(series, gate: config.gate))
            }
        }
    }

    private func duration(_ p: Phase) -> Int64 { p == .stabilising ? config.stabilisationMs : config.measurementMs }
}
