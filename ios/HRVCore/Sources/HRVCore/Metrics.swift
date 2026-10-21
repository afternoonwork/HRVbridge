import Foundation

public struct ArtefactFilterConfig: Equatable, Sendable {
    public var minRrMs = 300.0
    public var maxRrMs = 2000.0
    /// Reject a beat differing more than this fraction from the reference median.
    public var maxRelativeDeviation = 0.20
    /// Number of previous accepted beats the reference median is taken over.
    public var medianWindow = 5

    public init() {}
}

public enum RejectReason: String, Codable, Sendable {
    case outOfRange = "OUT_OF_RANGE"
    case relative = "RELATIVE"
}

public struct FilteredSeries: Equatable, Sendable {
    public var rrMs: [Double]
    public var accepted: [Bool]
    /// nil where the beat was accepted.
    public var rejectReasons: [RejectReason?]

    public var acceptedCount: Int { accepted.filter { $0 }.count }
    public var nn: [Double] { zip(rrMs, accepted).filter { $0.1 }.map { $0.0 } }
}

public enum ArtefactFilter {
    /// Range filter, then relative filter against the median of the previous accepted
    /// beats. Before any beat is accepted the reference is the median of the first
    /// `medianWindow` in-range beats, so a first-beat artefact cannot become the reference.
    public static func filter(_ rrMs: [Double], config: ArtefactFilterConfig = ArtefactFilterConfig()) -> FilteredSeries {
        func inRange(_ x: Double) -> Bool { x >= config.minRrMs && x <= config.maxRrMs }
        let seedValues = Array(rrMs.filter(inRange).prefix(config.medianWindow))
        let seed = seedValues.isEmpty ? 0.0 : median(seedValues)
        var accepted: [Bool] = []
        var reasons: [RejectReason?] = []
        var acceptedValues: [Double] = []
        for x in rrMs {
            var reason: RejectReason?
            if !inRange(x) {
                reason = .outOfRange
            } else {
                let ref = acceptedValues.isEmpty ? seed : median(Array(acceptedValues.suffix(config.medianWindow)))
                if abs(x - ref) / ref > config.maxRelativeDeviation { reason = .relative }
            }
            accepted.append(reason == nil)
            reasons.append(reason)
            if reason == nil { acceptedValues.append(x) }
        }
        return FilteredSeries(rrMs: rrMs, accepted: accepted, rejectReasons: reasons)
    }

    public static func median(_ values: [Double]) -> Double {
        precondition(!values.isEmpty)
        let s = values.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}

/// Shared rounding rule (test-vectors.json "roundingRule"), for positive values:
/// n = floor(x * 1e9 + 0.5) in IEEE double (an exact integer), then n / 10^9 rounded
/// half up in exact integer arithmetic. Gives the same results as Android's BigDecimal path.
public enum Rounding {
    public static func halfUp(_ x: Double, decimals: Int) -> Double {
        let q = scaled(x, decimals)
        var p = 1.0
        for _ in 0..<decimals { p *= 10 }
        return Double(q) / p
    }

    public static func halfUpToInt(_ x: Double) -> Int { Int(scaled(x, 0)) }

    private static func scaled(_ x: Double, _ decimals: Int) -> Int64 {
        precondition(x >= 0 && x < 1e6, "value out of range: \(x)")
        precondition((0...9).contains(decimals))
        let nano = Int64((x * 1e9 + 0.5).rounded(.down))
        var div: Int64 = 1
        for _ in 0..<(9 - decimals) { div *= 10 }
        return (nano + div / 2) / div
    }
}

public struct QualityGate: Equatable, Sendable {
    /// Unreliable if strictly more than this fraction of beats was removed.
    public var maxRemovedFraction = 0.05
    public var minValidBeats = 40

    public init() {}
}

public struct HrvResult: Equatable, Sendable {
    public var meanNnMs: Double
    /// round(60000 / mean NN), bpm.
    public var restingHr: Int
    public var rmssdMs: Double
    public var sdnnMs: Double
    public var acceptedBeats: Int
    public var removedBeats: Int
    public var validPairs: Int
    /// false: "Measurement unreliable, please repeat"; upload only on user override.
    public var reliable: Bool

    public var rmssdRounded: Double { HrvMetrics.round1(rmssdMs) }
    public var sdnnRounded: Double { HrvMetrics.round1(sdnnMs) }
}

public enum HrvMetrics {
    /// nil when there are fewer than two accepted beats or no valid successive pair.
    public static func compute(_ series: FilteredSeries, gate: QualityGate = QualityGate()) -> HrvResult? {
        let rr = series.rrMs
        let ok = series.accepted
        var sumSq = 0.0
        var pairs = 0
        if rr.count >= 2 {
            for i in 0..<(rr.count - 1) where ok[i] && ok[i + 1] {
                let d = rr[i + 1] - rr[i]
                sumSq += d * d
                pairs += 1
            }
        }
        let nn = series.nn
        guard nn.count >= 2, pairs > 0 else { return nil }
        // Plain sequential sums so results match the reference generator exactly.
        var total = 0.0
        for x in nn { total += x }
        let mean = total / Double(nn.count)
        var ss = 0.0
        for x in nn { ss += (x - mean) * (x - mean) }
        let removed = rr.count - nn.count
        return HrvResult(
            meanNnMs: mean,
            restingHr: Rounding.halfUpToInt(60000.0 / mean),
            rmssdMs: (sumSq / Double(pairs)).squareRoot(),
            sdnnMs: (ss / Double(nn.count - 1)).squareRoot(),
            acceptedBeats: nn.count,
            removedBeats: removed,
            validPairs: pairs,
            reliable: Double(removed) / Double(rr.count) <= gate.maxRemovedFraction && nn.count >= gate.minValidBeats
        )
    }

    /// One decimal place, see `Rounding`.
    public static func round1(_ x: Double) -> Double { Rounding.halfUp(x, decimals: 1) }
}
