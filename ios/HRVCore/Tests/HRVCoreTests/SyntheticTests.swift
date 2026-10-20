@testable import HRVCore
import XCTest

final class SyntheticTests: XCTestCase {
    func testRestingRhythmIsDeterministicQuantisedAndPlausible() {
        let a = SyntheticRR.resting(beats: 200)
        XCTAssertEqual(a, SyntheticRR.resting(beats: 200))
        XCTAssertEqual(a.count, 200)
        // Quantised to 1/1024 s like a real strap, so encode/parse is lossless.
        XCTAssertTrue(a.allSatisfy { Double(HeartRatePacketEncoder.msToRaw($0)) * 1000.0 / 1024.0 == $0 })
        let mean = a.reduce(0, +) / Double(a.count)
        XCTAssertEqual(mean, 1000, accuracy: 10)
        XCTAssertNotEqual(SyntheticRR.resting(beats: 50, seed: 1), SyntheticRR.resting(beats: 50, seed: 2))
    }

    func testRestingRhythmGivesReliableResult() {
        let r = HrvMetrics.compute(ArtefactFilter.filter(SyntheticRR.resting(beats: 70)))!
        XCTAssertTrue(r.reliable)
        XCTAssertEqual(r.restingHr, 60)
        XCTAssertGreaterThan(r.rmssdMs, 20)
    }

    func testArtefactsMakeItUnreliable() {
        let rr = SyntheticRR.withDropouts(SyntheticRR.resting(beats: 70), every: 12)
        XCTAssertEqual(rr.filter { $0 == 250 }.count, 5)
        XCTAssertFalse(HrvMetrics.compute(ArtefactFilter.filter(rr))!.reliable)
    }

    func testSimulatorScenariosRunThroughTheSession() {
        // Every scenario must cover check + 90 s settling + 60 s measurement.
        for s in SimulatorScenario.allCases {
            let packets = s.packets()
            XCTAssertGreaterThanOrEqual(packets.last!.timeMs, 170_000, s.title)
        }
        let session = MeasurementSession(config: SessionConfig(), startMs: 0)
        for p in SimulatorScenario.calm.packets() { session.onPacket(p.bytes, timeMs: p.timeMs) }
        guard case let .finished(_, _, result) = session.state else { return XCTFail("\(session.state)") }
        XCTAssertTrue(result!.reliable)

        let noRr = MeasurementSession(config: SessionConfig(), startMs: 0)
        for p in SimulatorScenario.noBeatToBeat.packets() { noRr.onPacket(p.bytes, timeMs: p.timeMs) }
        XCTAssertEqual(noRr.state, .failed(.noRR))

        let artefacts = MeasurementSession(config: SessionConfig(), startMs: 0)
        for p in SimulatorScenario.artefacts.packets() { artefacts.onPacket(p.bytes, timeMs: p.timeMs) }
        guard case let .finished(_, _, bad) = artefacts.state else { return XCTFail("\(artefacts.state)") }
        XCTAssertFalse(bad!.reliable)
    }
}
