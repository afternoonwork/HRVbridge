@testable import HRVCore
import XCTest

/// Paul's real Polar H9 capture through parser, filter, metrics and the measurement
/// session. Expected values: tools/gen_test_vectors.py --recordings.
final class RecordingTests: XCTestCase {
    struct Expected: Decodable {
        struct Session: Decodable {
            let checkMs: Int64, stabilisationMs: Int64, measurementMs: Int64
            let rrMs: [Double]
            let accepted: [Bool]
            let metrics: TestVectors.Series.Metrics
        }

        let parsedPackets: Int
        let packetsWithRr: Int
        let rrMs: [Double]
        let accepted: [Bool]
        let rejectReasons: [String?]
        let metrics: TestVectors.Series.Metrics
        let session: Session
    }

    static let dir = TestVectors.url.deletingLastPathComponent().appendingPathComponent("recordings")
    let recording = try! RawRecording(jsonData: Data(contentsOf: dir.appendingPathComponent("polar-h9-2026-10-21.json")))
    let expected = try! JSONDecoder().decode(Expected.self, from: Data(contentsOf: dir.appendingPathComponent("polar-h9-2026-10-21.expected.json")))

    func assertMetrics(_ e: TestVectors.Series.Metrics, _ r: HrvResult?, file: StaticString = #filePath, line: UInt = #line) {
        guard let r else { return XCTFail("no result", file: file, line: line) }
        XCTAssertClose(r.meanNnMs, e.meanNnMs, "mean")
        XCTAssertClose(r.rmssdMs, e.rmssdMs, "rmssd")
        XCTAssertClose(r.sdnnMs, e.sdnnMs, "sdnn")
        XCTAssertEqual(r.restingHr, e.restingHr, file: file, line: line)
        XCTAssertEqual(r.rmssdRounded, e.rmssdRounded, file: file, line: line)
        XCTAssertEqual(r.sdnnRounded, e.sdnnRounded, file: file, line: line)
        XCTAssertEqual(r.acceptedBeats, e.acceptedBeats, file: file, line: line)
        XCTAssertEqual(r.removedBeats, e.removedBeats, file: file, line: line)
        XCTAssertEqual(r.validPairs, e.validPairs, file: file, line: line)
        XCTAssertEqual(r.reliable, e.reliable, file: file, line: line)
    }

    func testWholeRecordingThroughParserFilterAndMetrics() {
        let parsed = recording.packets.map { HeartRateParser.parse($0.bytes) }
        XCTAssertEqual(parsed.compactMap { $0 }.count, expected.parsedPackets)
        XCTAssertEqual(parsed.filter { !($0?.rrIntervalsMs.isEmpty ?? true) }.count, expected.packetsWithRr)
        let rr = parsed.flatMap { $0?.rrIntervalsMs ?? [] }
        XCTAssertEqual(rr, expected.rrMs)
        let series = ArtefactFilter.filter(rr)
        XCTAssertEqual(series.accepted, expected.accepted)
        XCTAssertEqual(series.rejectReasons.map { $0?.rawValue }, expected.rejectReasons)
        assertMetrics(expected.metrics, HrvMetrics.compute(series))
    }

    func testMeasurementSessionOverTheRecording() {
        let e = expected.session
        let config = SessionConfig(checkMs: e.checkMs, stabilisationMs: e.stabilisationMs, measurementMs: e.measurementMs)
        let s = MeasurementSession(config: config, startMs: 0)
        for p in recording.packets { s.onPacket(p.bytes, timeMs: p.tMs) }
        s.onTick(e.checkMs + e.stabilisationMs + e.measurementMs)
        guard case let .finished(rr, series, result) = s.state else { return XCTFail("\(s.state)") }
        XCTAssertEqual(rr, e.rrMs)
        XCTAssertEqual(series.accepted, e.accepted)
        assertMetrics(e.metrics, result)
    }
}
