import Foundation

/// One decoded Heart Rate Measurement (0x2A37) notification.
public struct HeartRateMeasurement: Equatable, Sendable {
    public var heartRate: Int
    public var contactSupported: Bool
    public var contactDetected: Bool
    public var energyExpended: Int?
    /// Flags bit4: the sensor sends RR intervals (the list may still be empty).
    public var rrPresent: Bool
    /// Oldest first, in ms, unrounded (raw * 1000 / 1024).
    public var rrIntervalsMs: [Double]
    /// An odd byte after the RR values was ignored (malformed packet).
    public var droppedTrailingByte: Bool

    public init(heartRate: Int, contactSupported: Bool, contactDetected: Bool, energyExpended: Int?,
                rrPresent: Bool, rrIntervalsMs: [Double], droppedTrailingByte: Bool) {
        self.heartRate = heartRate
        self.contactSupported = contactSupported
        self.contactDetected = contactDetected
        self.energyExpended = energyExpended
        self.rrPresent = rrPresent
        self.rrIntervalsMs = rrIntervalsMs
        self.droppedTrailingByte = droppedTrailingByte
    }

    /// Contact feature supported but no contact: ask the user to moisten the electrodes.
    public var poorContact: Bool { contactSupported && !contactDetected }
}

public enum HeartRateParser {
    public static func rawToMs(_ raw: Int) -> Double { Double(raw) * 1000.0 / 1024.0 }

    /// Returns nil if the packet is too short for the fields its flags announce.
    public static func parse<C: Collection>(_ bytes: C) -> HeartRateMeasurement? where C.Element == UInt8 {
        let b = Array(bytes)
        guard b.count >= 2 else { return nil }
        let flags = Int(b[0])
        var i: Int
        let hr: Int
        if flags & 0x01 != 0 {
            guard b.count >= 3 else { return nil }
            hr = u16(b, 1)
            i = 3
        } else {
            hr = Int(b[1])
            i = 2
        }
        var energy: Int?
        if flags & 0x08 != 0 {
            guard b.count >= i + 2 else { return nil }
            energy = u16(b, i)
            i += 2
        }
        var rr: [Double] = []
        var dropped = false
        let rrPresent = flags & 0x10 != 0
        if rrPresent {
            while i + 1 < b.count {
                rr.append(rawToMs(u16(b, i)))
                i += 2
            }
            dropped = i < b.count
        }
        return HeartRateMeasurement(heartRate: hr, contactSupported: flags & 0x04 != 0, contactDetected: flags & 0x02 != 0,
                                    energyExpended: energy, rrPresent: rrPresent, rrIntervalsMs: rr, droppedTrailingByte: dropped)
    }

    private static func u16(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) | (Int(b[i + 1]) << 8) }
}

/// A raw 0x2A37 notification and its arrival time (ms on the session clock).
public struct TimedPacket: Equatable, Sendable {
    public var timeMs: Int64
    public var bytes: [UInt8]

    public init(timeMs: Int64, bytes: [UInt8]) {
        self.timeMs = timeMs
        self.bytes = bytes
    }
}

/// Builds 0x2A37 packets, the inverse of `HeartRateParser`.
public enum HeartRatePacketEncoder {
    public static func msToRaw(_ ms: Double) -> Int { Int((ms * 1024.0 / 1000.0).rounded()) }

    public static func encode(heartRate: Int, rrRaw: [Int], contactDetected: Bool = true, rrPresent: Bool = true) -> [UInt8] {
        let uint16 = heartRate > 255
        var flags = 0x04
        if uint16 { flags |= 0x01 }
        if contactDetected { flags |= 0x02 }
        if rrPresent { flags |= 0x10 }
        var out: [UInt8] = [UInt8(flags), UInt8(truncatingIfNeeded: heartRate)]
        if uint16 { out.append(UInt8(truncatingIfNeeded: heartRate >> 8)) }
        if rrPresent {
            for raw in rrRaw {
                out.append(UInt8(truncatingIfNeeded: raw))
                out.append(UInt8(truncatingIfNeeded: raw >> 8))
            }
        }
        return out
    }
}

/// Synthetic sensor output for tests and the debug simulator (mirrors Android's SimulatedStream).
public enum SimulatedStream {
    /// One notification every `intervalMs` carrying the RR values of the beats that ended since the previous one.
    public static func fromRr(_ rrMs: [Double], intervalMs: Int64 = 1000, poorContactUntilMs: Int64 = 0) -> [TimedPacket] {
        var packets: [TimedPacket] = []
        var beatEnd = 0.0
        var next = 0
        var heartRate = rrMs.isEmpty ? 60 : Int((60000.0 / rrMs[0]).rounded())
        var t = intervalMs
        while next < rrMs.count {
            var beats: [Double] = []
            while next < rrMs.count, beatEnd + rrMs[next] <= Double(t) {
                beatEnd += rrMs[next]
                beats.append(rrMs[next])
                next += 1
            }
            if !beats.isEmpty { heartRate = Int((60000.0 / (beats.reduce(0, +) / Double(beats.count))).rounded()) }
            let bytes = HeartRatePacketEncoder.encode(heartRate: heartRate, rrRaw: beats.map(HeartRatePacketEncoder.msToRaw),
                                                      contactDetected: t > poorContactUntilMs)
            packets.append(TimedPacket(timeMs: t, bytes: bytes))
            t += intervalMs
        }
        return packets
    }

    /// A sensor that never sends RR intervals.
    public static func heartRateOnly(heartRate: Int, durationMs: Int64, intervalMs: Int64 = 1000) -> [TimedPacket] {
        stride(from: intervalMs, through: durationMs, by: Int(intervalMs)).map {
            TimedPacket(timeMs: $0, bytes: HeartRatePacketEncoder.encode(heartRate: heartRate, rrRaw: [], rrPresent: false))
        }
    }
}
