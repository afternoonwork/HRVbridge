@testable import HRVCore
import XCTest

/// Optional morning questions: Intervals.icu wellness fields 1 to 4 and a sick-day event.
final class ReadinessTests: XCTestCase {
    let day = LocalDate(year: 2026, month: 10, day: 20)

    func testOnlyAnsweredFieldsAreSent() {
        var v = WellnessValues(date: day, restingHr: 52, hrvRmssd: 68.4, hrvSdnn: 61.2)
        v.answers = ReadinessAnswers(soreness: 2, injury: 1)
        XCTAssertEqual(Intervals.wellnessBody(v), #"{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2,"soreness":2,"injury":1}"#)
        v.answers = ReadinessAnswers(soreness: 1, fatigue: 2, motivation: 3, injury: 4, sick: true)
        XCTAssertEqual(Intervals.bulkBody([v]),
                       #"[{"id":"2026-10-20","restingHR":52,"hrv":68.4,"hrvSDNN":61.2,"soreness":1,"fatigue":2,"motivation":3,"injury":4}]"#)
        v.answers = ReadinessAnswers()
        XCTAssertEqual(Intervals.wellnessBody(v), #"{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2}"#)
    }

    func testAnswersOutsideOneToFourAreDropped() {
        let a = ReadinessAnswers(soreness: 0, fatigue: 5, motivation: 4)
        XCTAssertNil(a.soreness)
        XCTAssertNil(a.fatigue)
        XCTAssertEqual(a.motivation, 4)
        XCTAssertFalse(a.isEmpty)
        XCTAssertTrue(ReadinessAnswers(sick: false).isEmpty)
    }

    func testSickEventRequest() {
        let spec = Intervals.sickEvent(day, apiKey: ApiKey(userInput: "abc123")!, base: "http://127.0.0.1:1")
        XCTAssertEqual(spec.method, "POST")
        XCTAssertEqual(spec.url, "http://127.0.0.1:1/api/v1/athlete/0/events?upsertOnUid=true")
        XCTAssertEqual(spec.headers["Authorization"], "Basic QVBJX0tFWTphYmMxMjM=")
        XCTAssertEqual(spec.headers["Content-Type"], "application/json")
        XCTAssertEqual(String(decoding: spec.body, as: UTF8.self),
                       #"{"category":"SICK","start_date_local":"2026-10-20T00:00:00","name":"Sick","uid":"hrvbridge-sick-2026-10-20"}"#)
    }

    func testRecordKeepsAnswers() throws {
        let s = ArtefactFilter.filter((0..<50).map { $0 % 2 == 0 ? 1000.0 : 1010.0 })
        var r = SessionRecord(startedAt: Date(timeIntervalSince1970: 1_792_474_200), date: day, config: SessionConfig(),
                              series: s, result: HrvMetrics.compute(s), malformedPackets: 0, uploadedAt: nil)
        XCTAssertNil(try SessionRecord(jsonData: r.jsonData()).answers)
        r.answers = ReadinessAnswers(fatigue: 3, sick: true)
        let json = String(decoding: try r.jsonData(), as: UTF8.self)
        XCTAssertTrue(json.contains(#""answers":{"fatigue":3,"injury":null,"motivation":null,"sick":true,"soreness":null}"#), json)
        XCTAssertEqual(try SessionRecord(jsonData: r.jsonData()), r)
        // Values for the upload include the answers.
        XCTAssertEqual(r.wellnessValues?.answers, ReadinessAnswers(fatigue: 3, sick: true))
    }
}
