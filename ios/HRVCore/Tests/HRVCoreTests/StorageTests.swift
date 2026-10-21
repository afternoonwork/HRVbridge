@testable import HRVCore
import XCTest

final class StorageTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("hrvcore-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    func record(_ start: String, beats: Int = 50, uploadedAt: Date? = nil, override: Bool = false) -> SessionRecord {
        let s = ArtefactFilter.filter((0..<beats).map { $0 % 2 == 0 ? 1000.0 : 1010.0 })
        return SessionRecord(startedAt: ISO8601DateFormatter().date(from: start)!, date: LocalDate(string: String(start.prefix(10)))!,
                             config: SessionConfig(), series: s, result: HrvMetrics.compute(s), malformedPackets: 0,
                             uploadedAt: uploadedAt, uploadOverride: override)
    }

    func testRecordRoundTripKeepsRawRr() throws {
        let series = ArtefactFilter.filter([800, 850, 250, 820, 880, 999.0234375])
        let r = SessionRecord(startedAt: Date(timeIntervalSince1970: 1_792_474_200.123), date: LocalDate(year: 2026, month: 10, day: 20),
                              config: SessionConfig(), series: series, result: HrvMetrics.compute(series), malformedPackets: 1,
                              uploadedAt: nil, uploadOverride: true)
        let data = try r.jsonData()
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("999.0234375"))
        let back = try SessionRecord(jsonData: data)
        XCTAssertEqual(back, r)
        XCTAssertEqual(r.fileName, "session-2026-10-20T05-30-00Z.json")
    }

    func testReadsAndroidWrittenRecord() throws {
        // Exactly what Android's SessionRecord.toJson() writes (key order differs from ours; irrelevant).
        let android = #"{"version":1,"startedAt":"2026-10-20T05:30:00Z","date":"2026-10-20","config":{"checkMs":10000,"stabilisationMs":60000,"measurementMs":60000,"minRrMs":300.0,"maxRrMs":2000.0,"maxRelativeDeviation":0.2,"medianWindow":5,"maxRemovedFraction":0.05,"minValidBeats":40},"malformedPackets":0,"rrMs":[1000.0,250.0,1010.0],"accepted":[true,false,true],"rejectReasons":[null,"OUT_OF_RANGE",null],"result":{"meanNnMs":1005.0,"restingHr":60,"rmssdMs":10.0,"sdnnMs":7.0710678118654755,"rmssdRounded":10.0,"sdnnRounded":7.1,"acceptedBeats":2,"removedBeats":1,"validPairs":1,"reliable":false},"uploadedAt":null}"#
        let r = try SessionRecord(jsonData: Data(android.utf8))
        XCTAssertEqual(r.series.rejectReasons, [nil, .outOfRange, nil])
        XCTAssertEqual(r.result?.restingHr, 60)
        XCTAssertFalse(r.uploadOverride) // field absent in older files
        XCTAssertEqual(r.config, SessionConfig())
    }

    func testStorePendingAndRetryBatch() throws {
        let store = SessionStore(directory: dir)
        try store.save(record("2026-10-18T05:30:00Z", override: true))
        try store.save(record("2026-10-19T05:30:00Z", override: true))
        try store.save(record("2026-10-19T06:10:00Z", override: true))
        try store.save(record("2026-10-17T05:30:00Z", uploadedAt: Date(), override: true))
        try store.save(record("2026-10-16T05:30:00Z", beats: 10))
        try store.save(record("2026-10-15T05:30:00Z", beats: 10, override: true))
        try store.save(record("2026-10-14T05:30:00Z")) // reliable but never submitted
        XCTAssertEqual(store.list().count, 7)
        XCTAssertEqual(store.pendingUploads().map { $0.date.description }, ["2026-10-19", "2026-10-19", "2026-10-18", "2026-10-15"])
        XCTAssertEqual(store.retryBatch().map { ISO8601DateFormatter().string(from: $0.startedAt) },
                       ["2026-10-19T06:10:00Z", "2026-10-18T05:30:00Z", "2026-10-15T05:30:00Z"])
        // A newer uploaded value of the same day is never overwritten.
        try store.save(record("2026-10-19T06:10:00Z", uploadedAt: Date(), override: true))
        XCTAssertFalse(store.retryBatch().contains { $0.date.description == "2026-10-19" })
        try Data("{".utf8).write(to: dir.appendingPathComponent("session-garbage.json"))
        XCTAssertEqual(store.list().count, 7)
    }

    func testRetryIgnoresNewerUnsubmittedSession() throws {
        let store = SessionStore(directory: dir)
        try store.save(record("2026-10-19T05:30:00Z", override: true))
        try store.save(record("2026-10-19T06:10:00Z")) // newer, never submitted
        XCTAssertEqual(store.retryBatch().map { ISO8601DateFormatter().string(from: $0.startedAt) },
                       ["2026-10-19T05:30:00Z"])
    }

    func testRetryNeverOverwritesNewerSubmittedSession() throws {
        let store = SessionStore(directory: dir)
        try store.save(record("2026-10-19T05:30:00Z", override: true))
        try store.save(record("2026-10-19T06:10:00Z", uploadedAt: Date(), override: true))
        XCTAssertTrue(store.retryBatch().isEmpty)
    }

    func testDeleteAllRemovesOnlySessionFiles() throws {
        let store = SessionStore(directory: dir)
        try store.save(record("2026-10-19T05:30:00Z"))
        try store.save(record("2026-10-20T05:30:00Z"))
        try Data("keep".utf8).write(to: dir.appendingPathComponent("other.txt"))
        XCTAssertEqual(store.deleteAll(), 2)
        XCTAssertEqual(store.list().count, 0)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["other.txt"])
        XCTAssertEqual(SessionStore(directory: dir.appendingPathComponent("missing")).deleteAll(), 0)
    }

    func testRawRecordingJson() throws {
        var rec = RawRecording(startedAt: Date(timeIntervalSince1970: 1_792_474_200), deviceName: "Polar H9 ABCD", deviceId: "UUID-1")
        rec.add(bytes: [0x16, 60, 0x00, 0x04], timeMs: 1_000)
        rec.add(bytes: [0x16, 61], timeMs: 2_001)
        let data = try rec.jsonData()
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains(#""163C0004""#), text)
        let back = try RawRecording(jsonData: data)
        XCTAssertEqual(back, rec)
        XCTAssertEqual(back.packets.map(\.bytes), [[0x16, 60, 0x00, 0x04], [0x16, 61]])
    }

    func testDebugLogRedactsSecrets() {
        var now: Int64 = 0
        let log = DebugLog(clockMs: { now })
        log.addSecret("abc123")
        log.addSecret(Intervals.basicAuth(apiKey: "abc123").components(separatedBy: " ")[1])
        log.add("BLE", "connected")
        now = 1_234
        log.add("HTTP", "oops abc123 QVBJX0tFWTphYmMxMjM=")
        XCTAssertEqual(log.text(), "[0.000 s] BLE connected\n[1.234 s] HTTP oops [redacted] [redacted]")
        XCTAssertEqual(DebugLog.describePacket([0x16, 0x3C, 0x00, 0x04], HeartRateParser.parse([0x16, 0x3C, 0x00, 0x04])),
                       "16 3C 00 04 -> HR 60, contact ok, RR [1000.0]")
    }
}
