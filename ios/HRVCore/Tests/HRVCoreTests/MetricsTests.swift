@testable import HRVCore
import XCTest

final class MetricsTests: XCTestCase {
    func testFilterMatchesSharedVectors() {
        for s in TestVectors.shared.series {
            let r = ArtefactFilter.filter(s.rrMs)
            XCTAssertEqual(r.accepted, s.expected.accepted, s.name)
            XCTAssertEqual(r.rejectReasons.map { $0?.rawValue }, s.expected.rejectReasons, s.name)
        }
    }

    func testMetricsMatchSharedVectors() {
        for s in TestVectors.shared.series {
            let r = HrvMetrics.compute(ArtefactFilter.filter(s.rrMs))
            guard let e = s.expected.metrics else {
                XCTAssertNil(r, s.name)
                continue
            }
            guard let r = r else { return XCTFail("nil for \(s.name)") }
            XCTAssertClose(r.meanNnMs, e.meanNnMs, "\(s.name) mean")
            XCTAssertClose(r.rmssdMs, e.rmssdMs, "\(s.name) rmssd")
            XCTAssertClose(r.sdnnMs, e.sdnnMs, "\(s.name) sdnn")
            XCTAssertEqual(r.restingHr, e.restingHr, s.name)
            XCTAssertEqual(r.rmssdRounded, e.rmssdRounded, s.name)
            XCTAssertEqual(r.sdnnRounded, e.sdnnRounded, s.name)
            XCTAssertEqual(r.acceptedBeats, e.acceptedBeats, s.name)
            XCTAssertEqual(r.removedBeats, e.removedBeats, s.name)
            XCTAssertEqual(r.validPairs, e.validPairs, s.name)
            XCTAssertEqual(r.reliable, e.reliable, s.name)
        }
    }

    func testRoundingMatchesSharedVectors() {
        XCTAssertFalse(TestVectors.shared.rounding.isEmpty)
        for c in TestVectors.shared.rounding {
            XCTAssertEqual(Rounding.halfUp(c.value, decimals: c.decimals), c.expected, "\(c.value) to \(c.decimals)")
        }
        XCTAssertEqual(Rounding.halfUpToInt(72.5), 73)
        XCTAssertEqual(HrvMetrics.round1(1.15), 1.2)
    }

    func testHandComputedExample() {
        let r = HrvMetrics.compute(ArtefactFilter.filter([800, 850, 820, 880]))!
        XCTAssertEqual(r.restingHr, 72)
        XCTAssertEqual(r.rmssdRounded, 48.3)
        XCTAssertEqual(r.sdnnRounded, 35.0)
        XCTAssertFalse(r.reliable)
    }

    func testHeartRateShiftRejectsRestAndFailsGate() {
        let rr = Array(repeating: 1000.0, count: 40) + Array(repeating: 750.0, count: 30)
        let f = ArtefactFilter.filter(rr)
        XCTAssertEqual(f.accepted, Array(repeating: true, count: 40) + Array(repeating: false, count: 30))
        XCTAssertFalse(HrvMetrics.compute(f)!.reliable)
    }

    func testSignalCheck() {
        func m(_ hr: Int, _ rr: [Double], rrPresent: Bool = true, detected: Bool = true) -> HeartRateMeasurement {
            HeartRateMeasurement(heartRate: hr, contactSupported: true, contactDetected: detected, energyExpended: nil,
                                 rrPresent: rrPresent, rrIntervalsMs: rr, droppedTrailingByte: false)
        }
        XCTAssertEqual(SignalCheck.evaluate([]), .noData)
        XCTAssertEqual(SignalCheck.evaluate([m(60, [1000]), m(0, [], detected: false)]), .poorContact)
        XCTAssertEqual(SignalCheck.evaluate(Array(repeating: m(60, [], rrPresent: false), count: 10)), .noRR)
        XCTAssertEqual(SignalCheck.evaluate(Array(repeating: m(60, [1100]), count: 10)), .ok)
        XCTAssertEqual(SignalCheck.evaluate(Array(repeating: m(60, [1101]), count: 10)), .implausible)
        XCTAssertEqual(SignalCheck.evaluate(Array(repeating: m(60, [250]), count: 10)), .implausible)
        // One dropout and its HR spike are tolerated.
        let rr = TestVectors.series(named: "more than 5 percent removed").rrMs
        let window = SimulatedStream.fromRr(rr).filter { $0.timeMs < 10_000 }.map { HeartRateParser.parse($0.bytes)! }
        XCTAssertEqual(SignalCheck.evaluate(window), .ok)
    }
}
