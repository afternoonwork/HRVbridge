@testable import HRVCore
import XCTest

final class IntervalsTests: XCTestCase {
    let day = LocalDate(year: 2026, month: 10, day: 20)

    func testLocalDate() {
        XCTAssertEqual(day.description, "2026-10-20")
        XCTAssertEqual(LocalDate(year: 2026, month: 1, day: 5).description, "2026-01-05")
        XCTAssertEqual(LocalDate(string: "2026-10-20"), day)
        XCTAssertNil(LocalDate(string: "2026-13-01"))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
        // 23:30 UTC on 20 Oct is already 21 Oct in Berlin: the wellness record is the local day.
        let d = ISO8601DateFormatter().date(from: "2026-10-20T23:30:00Z")!
        XCTAssertEqual(LocalDate(date: d, calendar: cal), LocalDate(year: 2026, month: 10, day: 21))
    }

    func testUrlsAndBodies() {
        XCTAssertEqual(Intervals.wellnessURL(day), "https://intervals.icu/api/v1/athlete/0/wellness/2026-10-20")
        XCTAssertEqual(Intervals.wellnessBulkURL(), "https://intervals.icu/api/v1/athlete/0/wellness-bulk")
        let v = WellnessValues(date: day, restingHr: 52, hrvRmssd: 68.4, hrvSdnn: 61.0)
        XCTAssertEqual(Intervals.wellnessBody(v), #"{"restingHR":52,"hrv":68.4,"hrvSDNN":61.0}"#)
        let w = WellnessValues(date: LocalDate(year: 2026, month: 10, day: 21), restingHr: 50, hrvRmssd: 70.0, hrvSdnn: 55.5)
        XCTAssertEqual(
            Intervals.bulkBody([WellnessValues(date: day, restingHr: 52, hrvRmssd: 68.4, hrvSdnn: 61.2), w]),
            #"[{"id":"2026-10-20","restingHR":52,"hrv":68.4,"hrvSDNN":61.2},{"id":"2026-10-21","restingHR":50,"hrv":70.0,"hrvSDNN":55.5}]"#
        )
    }

    func testValuesUseRoundedMetrics() {
        let r = HrvMetrics.compute(ArtefactFilter.filter([800, 850, 820, 880]))!
        XCTAssertEqual(WellnessValues(date: day, result: r), WellnessValues(date: day, restingHr: 72, hrvRmssd: 48.3, hrvSdnn: 35.0))
    }

    func testAuthAndRequests() {
        XCTAssertEqual(Intervals.basicAuth(apiKey: "abc123"), "Basic QVBJX0tFWTphYmMxMjM=")
        XCTAssertEqual(ApiKey(userInput: "  abc123 \n")?.key, "abc123")
        XCTAssertNil(ApiKey(userInput: "   "))
        XCTAssertFalse(String(describing: ApiKey(userInput: "abc123")!).contains("abc123"))

        let v = WellnessValues(date: day, restingHr: 52, hrvRmssd: 68.4, hrvSdnn: 61.2)
        let req = Intervals.putWellness(v, apiKey: ApiKey(userInput: "abc123")!, base: "http://127.0.0.1:8080")
        XCTAssertEqual(req.method, "PUT")
        XCTAssertEqual(req.url, "http://127.0.0.1:8080/api/v1/athlete/0/wellness/2026-10-20")
        XCTAssertEqual(req.headers["Authorization"], "Basic QVBJX0tFWTphYmMxMjM=")
        XCTAssertEqual(req.headers["Content-Type"], "application/json")
        XCTAssertEqual(String(decoding: req.body, as: UTF8.self), #"{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2}"#)
        XCTAssertFalse(req.logDescription.contains("QVBJ"))
        XCTAssertTrue(req.logDescription.contains("PUT /api/v1/athlete/0/wellness/2026-10-20"))
        XCTAssertEqual(Intervals.putWellnessBulk([v], apiKey: ApiKey(userInput: "k")!).url,
                       "https://intervals.icu/api/v1/athlete/0/wellness-bulk")
    }

    func testResponseClassification() {
        XCTAssertEqual(ApiResult(statusCode: 200, body: "{}"), .success(code: 200, body: "{}"))
        XCTAssertEqual(ApiResult(statusCode: 401, body: ""), .unauthorised(code: 401))
        XCTAssertEqual(ApiResult(statusCode: 403, body: ""), .unauthorised(code: 403))
        XCTAssertEqual(ApiResult(statusCode: 500, body: "boom"), .httpError(code: 500, body: "boom"))
    }
}
