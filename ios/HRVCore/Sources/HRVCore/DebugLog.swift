import Foundation

/// In-memory log for "share debug log": BLE events, parsed packets, results, HTTP
/// status lines. Credentials must never be logged; registered secrets are also
/// redacted on export as a second line of defence. Thread-safe.
public final class DebugLog: @unchecked Sendable {
    private let lock = NSLock()
    private let capacity: Int
    private let clockMs: () -> Int64
    private var lines: [String] = []
    private var secrets: Set<String> = []
    private var dropped = 0

    public init(capacity: Int = 5_000, clockMs: @escaping () -> Int64) {
        self.capacity = capacity
        self.clockMs = clockMs
    }

    public func add(_ tag: String, _ message: String) {
        let t = clockMs()
        let ms = String(t % 1000)
        let line = "[\(t / 1000).\(String(repeating: "0", count: 3 - ms.count) + ms) s] \(tag) \(message)"
        lock.lock()
        defer { lock.unlock() }
        lines.append(line)
        if lines.count > capacity {
            lines.removeFirst(lines.count - capacity)
            dropped += 1
        }
    }

    public func addSecret(_ s: String) {
        guard !s.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        lock.lock()
        secrets.insert(s)
        lock.unlock()
    }

    public func clear() {
        lock.lock()
        lines.removeAll()
        dropped = 0
        lock.unlock()
    }

    public func text() -> String {
        lock.lock()
        defer { lock.unlock() }
        var body = (dropped > 0 ? "(\(dropped) older lines dropped)\n" : "") + lines.joined(separator: "\n")
        for s in secrets.sorted(by: { $0.count > $1.count }) {
            body = body.replacingOccurrences(of: s, with: "[redacted]")
        }
        return body
    }

    public static func describePacket(_ bytes: [UInt8], _ m: HeartRateMeasurement?) -> String {
        let hex = bytes.map { String(format: "%02X", $0) }.joined(separator: " ")
        guard let m = m else { return "\(hex) -> malformed" }
        let contact = !m.contactSupported ? "contact n/a" : (m.contactDetected ? "contact ok" : "poor contact")
        let rr = m.rrPresent ? "RR \(m.rrIntervalsMs)" : "no RR field"
        return "\(hex) -> HR \(m.heartRate), \(contact), \(rr)\(m.droppedTrailingByte ? ", dropped odd trailing byte" : "")"
    }
}
