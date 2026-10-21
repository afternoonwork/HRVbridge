@testable import HRVCore
import XCTest

final class SessionTests: XCTestCase {
    let fast = SessionConfig(checkMs: 10_000, stabilisationMs: 0, measurementMs: 60_000)

    func beat(_ ms: Double, contact: Bool = true) -> [UInt8] {
        HeartRatePacketEncoder.encode(heartRate: 60, rrRaw: [HeartRatePacketEncoder.msToRaw(ms)], contactDetected: contact)
    }

    func testDefaultTimingThroughAllPhases() {
        // No separate check phase: settling starts at once, the check runs on its first 10 s.
        let s = MeasurementSession(config: SessionConfig(), startMs: 0)
        XCTAssertEqual(s.state, .stabilising(remainingMs: 60_000, poorContact: false))
        for p in SimulatedStream.fromRr(Array(repeating: 1000.0, count: 200)) {
            s.onPacket(p.bytes, timeMs: p.timeMs)
            if p.timeMs == 40_000 { XCTAssertEqual(s.state, .stabilising(remainingMs: 20_000, poorContact: false)) }
            if p.timeMs == 100_000 { XCTAssertEqual(s.state, .measuring(remainingMs: 20_000, beats: 41, poorContact: false)) }
        }
        guard case let .finished(rr, _, result) = s.state else { return XCTFail("\(s.state)") }
        XCTAssertEqual(rr.count, 60)
        XCTAssertEqual(result?.restingHr, 60)
    }

    func testPoorContactBeforeCheckHoldsTheClock() {
        let s = MeasurementSession(config: fast, startMs: 0)
        s.onPacket(beat(1000), timeMs: 1_000)
        s.onPacket(beat(1000, contact: false), timeMs: 8_000)
        XCTAssertEqual(s.state, .measuring(remainingMs: 60_000, beats: 0, poorContact: true))
        s.onTick(12_000)
        XCTAssertEqual(s.state, .measuring(remainingMs: 60_000, beats: 0, poorContact: true))
        // Good contact again: the phase (and its 10 s check) starts over from here.
        s.onPacket(beat(1000), timeMs: 13_000)
        XCTAssertEqual(s.state, .measuring(remainingMs: 60_000, beats: 1, poorContact: false))
        for t in stride(from: Int64(14_000), through: 22_000, by: 1_000) { s.onPacket(beat(1000), timeMs: t) }
        s.onPacket(beat(1000), timeMs: 23_000)
        XCTAssertEqual(s.state, .measuring(remainingMs: 50_000, beats: 11, poorContact: false))
    }

    func testCheckRunsOnTheFirstTenSecondsOfSettling() {
        let s = MeasurementSession(config: SessionConfig(stabilisationMs: 30_000), startMs: 0)
        for t in stride(from: Int64(1_000), through: 9_000, by: 1_000) {
            s.onPacket(HeartRatePacketEncoder.encode(heartRate: 90, rrRaw: [1024]), timeMs: t)
        }
        XCTAssertEqual(s.state, .stabilising(remainingMs: 21_000, poorContact: false))
        s.onTick(10_000)
        XCTAssertEqual(s.state, .failed(.implausible))
    }

    func testPoorContactAfterCheckOnlyFlags() {
        let s = MeasurementSession(config: SessionConfig(stabilisationMs: 30_000), startMs: 0)
        for t in stride(from: Int64(1_000), through: 10_000, by: 1_000) { s.onPacket(beat(1000), timeMs: t) }
        s.onPacket(beat(1000, contact: false), timeMs: 12_000)
        XCTAssertEqual(s.state, .stabilising(remainingMs: 18_000, poorContact: true))
        s.onTick(30_000)
        XCTAssertEqual(s.state, .measuring(remainingMs: 60_000, beats: 0, poorContact: true))
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
            // 10 s settling (holding the check), then the measurement from 10 s.
            var cfg = fast
            cfg.stabilisationMs = 10_000
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
