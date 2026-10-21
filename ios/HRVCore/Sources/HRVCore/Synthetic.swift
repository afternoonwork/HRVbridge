import Foundation

/// Deterministic synthetic RR series (no real heart data), for the debug simulated strap.
public enum SyntheticRR {
    /// Resting-like rhythm around `baseMs`: respiratory sinus arrhythmia plus small
    /// pseudo-random noise (LCG), quantised to 1/1024 s like a real strap.
    public static func resting(beats: Int, baseMs: Double = 1000, seed: UInt64 = 12345) -> [Double] {
        var state = seed
        return (0..<beats).map { i in
            state = (1_103_515_245 &* state &+ 12345) % 2_147_483_648
            let noise = (Double(state) / 2_147_483_648.0 - 0.5) * 30.0
            let ms = baseMs + 45.0 * sin(2 * Double.pi * Double(i) / 4.5) + noise
            return Double(HeartRatePacketEncoder.msToRaw(ms)) * 1000.0 / 1024.0
        }
    }

    /// Replaces every `every`-th beat with a 250 ms dropout (removed by the range filter).
    public static func withDropouts(_ rr: [Double], every: Int) -> [Double] {
        rr.enumerated().map { (i, x) in (i + 1) % every == 0 ? 250.0 : x }
    }
}

/// What the debug simulated strap can replay.
public enum SimulatorScenario: String, CaseIterable, Identifiable, Sendable {
    case calm
    case artefacts
    case poorContactAtStart
    case noBeatToBeat

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .calm: return "Calm resting rhythm"
        case .artefacts: return "Rhythm with dropouts (unreliable)"
        case .poorContactAtStart: return "Poor contact for the first 15 s"
        case .noBeatToBeat: return "Sensor without beat-to-beat data"
        }
    }

    /// 1 Hz 0x2A37 packets, long enough for the check, 90 s settling and the measurement.
    public func packets() -> [TimedPacket] {
        switch self {
        case .calm: return SimulatedStream.fromRr(SyntheticRR.resting(beats: 220))
        case .artefacts: return SimulatedStream.fromRr(SyntheticRR.withDropouts(SyntheticRR.resting(beats: 220), every: 12))
        case .poorContactAtStart: return SimulatedStream.fromRr(SyntheticRR.resting(beats: 220), poorContactUntilMs: 15_000)
        case .noBeatToBeat: return SimulatedStream.heartRateOnly(heartRate: 60, durationMs: 220_000)
        }
    }
}

/// Endless packet feed for the simulated strap: the scenario once, then its steady part
/// looped (poor contact happens only at the very start), so the strap keeps sending for
/// as long as it is connected, whenever the user starts the measurement.
public struct SimulatorFeed {
    private let first: [TimedPacket]
    private let loop: [TimedPacket]
    private var current: [TimedPacket]
    private var index = 0
    private var offset: Int64 = 0

    public init(_ scenario: SimulatorScenario) {
        first = scenario.packets()
        loop = scenario == .poorContactAtStart ? SimulatorScenario.calm.packets() : first
        current = first
    }

    public mutating func next() -> TimedPacket {
        if index == current.count {
            offset += current.last?.timeMs ?? 0
            current = loop
            index = 0
        }
        let p = current[index]
        index += 1
        return TimedPacket(timeMs: offset + p.timeMs, bytes: p.bytes)
    }
}
