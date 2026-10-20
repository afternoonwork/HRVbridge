@testable import HRVCore
import XCTest

final class SessionTests: XCTestCase {
    let fast = SessionConfig(checkMs: 10_000, stabilisationMs: 0, measurementMs: 60_000)

    func beat(_ ms: Double, contact: Bool = true) -> [UInt8] {
        HeartRatePacketEncoder.encode(heartRate: 60, rrRaw: [HeartRatePacketEncoder.msToRaw(ms)], contactDetected: contact)
    }

    func testDefaultTimingThroughAllPhases() {
        let s = MeasurementSession(config: SessionConfig(), startMs: 0)
        XCTAssertEqual(s.state, .checking(elapsedMs: 0, poorContact: false))
        for p in SimulatedStream.fromRr(Array(repeating: 1000.0, count: 200)) {
            s.onPacket(p.bytes, timeMs: p.timeMs)
            if p.timeMs == 40_000 { XCTAssertEqual(s.state, .stabilising(remainingMs: 30_000, poorContact: false)) }
            if p.timeMs == 100_000 { XCTAssertEqual(s.state, .measuring(remainingMs: 30_000, beats: 31, poorContact: false)) }
        }
        guard case let .finished(rr, _, result) = s.state else { return XCTFail("\(s.state)") }
        XCTAssertEqual(rr.count, 60)
        XCTAssertEqual(result?.restingHr, 60)
    }

    func testPoorContactRestartsCheck() {
        let s = MeasurementSession(config: fast, startMs: 0)
        s.onPacket(beat(1000), timeMs: 1_000)
        s.onPacket(beat(1000, contact: false), timeMs: 8_000)
        XCTAssertEqual(s.state, .checking(elapsedMs: 0, poorContact: true))
        s.onTick(12_000)
        XCTAssertEqual(s.state, .checking(elapsedMs: 4_000, poorContact: true))
        s.onPacket(beat(1000), timeMs: 13_000)
        XCTAssertEqual(s.state, .checking(elapsedMs: 0, poorContact: false))
        for t in stride(from: Int64(14_000), through: 22_000, by: 1_000) { s.onPacket(beat(1000), timeMs: t) }
        s.onPacket(beat(1000), timeMs: 23_000)
        guard case .measuring = s.state else { return XCTFail("\(s.state)") }
    }

    func testFailures() {
        let noRr = MeasurementSession(config: fast, startMs: 0)
        for p in SimulatedStream.heartRateOnly(heartRate: 60, durationMs: 10_000) { noRr.onPacket(p.bytes, timeMs: p.timeMs) }
        XCTAssertEqual(noRr.state, .failed(.noRR))

        let noData = MeasurementSession(config: fast, startMs: 0)
        noData.onTick(10_000)
        XCTAssertEqual(noData.state, .failed(.noData))

        let off = MeasurementSession(config: fast, startMs: 0)
        off.onDisconnected()
        off.onPacket(beat(1000), timeMs: 1_000)
        XCTAssertEqual(off.state, .failed(.disconnected))

        let bad = MeasurementSession(config: fast, startMs: 0)
        bad.onPacket([0x10], timeMs: 1_000)
        XCTAssertEqual(bad.malformedPackets, 1)
    }

    func testPipelineReproducesVectorMetrics() {
        var checked = 0
        for v in TestVectors.shared.series {
            let rr = v.rrMs
            if rr.count < 2 || rr.contains(where: { Double(HeartRatePacketEncoder.msToRaw($0)) * 1000.0 / 1024.0 != $0 }) { continue }
            let total = Int64(rr.reduce(0, +))
            var cfg = fast
            cfg.measurementMs = total + 1_000
            let s = MeasurementSession(config: cfg, startMs: 0)
            for t in stride(from: Int64(500), through: 9_500, by: 1_000) { s.onPacket(beat(1000), timeMs: t) }
            var t = 10_000.0
            for x in rr {
                t += x
                s.onPacket(beat(x), timeMs: Int64(t))
            }
            s.onTick(10_000 + total + 1_000)
            guard case let .finished(got, _, result) = s.state else { return XCTFail("\(v.name): \(s.state)") }
            XCTAssertEqual(got, rr, v.name)
            XCTAssertEqual(result, HrvMetrics.compute(ArtefactFilter.filter(rr)), v.name)
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 5)
    }
}
