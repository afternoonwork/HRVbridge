import Foundation

/// A calendar day; the wellness record id is the user's local date.
public struct LocalDate: Equatable, Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
    public var year: Int
    public var month: Int
    public var day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public init?(string: String) {
        let p = string.split(separator: "-")
        guard p.count == 3, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        self.init(year: y, month: m, day: d)
    }

    public init(date: Date, calendar: Calendar = .current) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year!, month: c.month!, day: c.day!)
    }

    public var description: String {
        func pad(_ n: Int, _ width: Int) -> String {
            let s = String(n)
            return String(repeating: "0", count: max(0, width - s.count)) + s
        }
        return "\(pad(year, 4))-\(pad(month, 2))-\(pad(day, 2))"
    }

    public static func < (a: LocalDate, b: LocalDate) -> Bool {
        (a.year, a.month, a.day) < (b.year, b.month, b.day)
    }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = LocalDate(string: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date \(s)"))
        }
        self = d
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }
}

/// One day's values for the wellness record.
public struct WellnessValues: Equatable, Sendable {
    public var date: LocalDate
    public var restingHr: Int
    public var hrvRmssd: Double
    public var hrvSdnn: Double

    public init(date: LocalDate, restingHr: Int, hrvRmssd: Double, hrvSdnn: Double) {
        self.date = date
        self.restingHr = restingHr
        self.hrvRmssd = hrvRmssd
        self.hrvSdnn = hrvSdnn
    }

    public init(date: LocalDate, result: HrvResult) {
        self.init(date: date, restingHr: result.restingHr, hrvRmssd: result.rmssdRounded, hrvSdnn: result.sdnnRounded)
    }
}

/// The user's personal Intervals.icu API key (v1 connect method). Never printed.
public struct ApiKey: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let key: String

    /// Trims whitespace; nil if nothing is left.
    public init?(userInput: String) {
        let k = userInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else { return nil }
        key = k
    }

    public var authorizationHeader: String { Intervals.basicAuth(apiKey: key) }
    public var description: String { "ApiKey(***)" }
    public var debugDescription: String { description }
}

/// A request to send, independent of URLSession (the app converts it).
public struct HTTPRequestSpec: Equatable, Sendable {
    public var method: String
    public var url: String
    public var headers: [String: String]
    public var body: Data

    /// Method and path only: safe for the debug log (no headers, no query).
    public var logDescription: String {
        "\(method) \(URL(string: url)?.path ?? url)"
    }
}

public enum ApiResult: Equatable, Sendable {
    case success(code: Int, body: String)
    /// 401/403: the key is no longer valid; ask the user to reconnect.
    case unauthorised(code: Int)
    case httpError(code: Int, body: String)
    case networkError(message: String)

    public init(statusCode: Int, body: String) {
        switch statusCode {
        case 401, 403: self = .unauthorised(code: statusCode)
        case 200...299: self = .success(code: statusCode, body: body)
        default: self = .httpError(code: statusCode, body: body)
        }
    }
}

/// Intervals.icu wellness API request building. Athlete id 0 means the key's athlete.
public enum Intervals {
    public static let baseURL = "https://intervals.icu"

    /// PUT target; only supplied fields change, so retries are idempotent.
    public static func wellnessURL(_ date: LocalDate, base: String = baseURL) -> String {
        "\(base)/api/v1/athlete/0/wellness/\(date)"
    }

    public static func wellnessBulkURL(base: String = baseURL) -> String {
        "\(base)/api/v1/athlete/0/wellness-bulk"
    }

    /// Exactly `{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2}`, same as Android.
    public static func wellnessBody(_ v: WellnessValues) -> String { "{\(fields(v))}" }

    public static func bulkBody(_ values: [WellnessValues]) -> String {
        "[" + values.map { #"{"id":"\#($0.date)",\#(fields($0))}"# }.joined(separator: ",") + "]"
    }

    /// Personal API key: Basic auth with username API_KEY.
    public static func basicAuth(apiKey: String) -> String {
        "Basic " + Data("API_KEY:\(apiKey)".utf8).base64EncodedString()
    }

    public static func putWellness(_ v: WellnessValues, apiKey: ApiKey, base: String = baseURL) -> HTTPRequestSpec {
        request("PUT", wellnessURL(v.date, base: base), apiKey, wellnessBody(v))
    }

    public static func putWellnessBulk(_ values: [WellnessValues], apiKey: ApiKey, base: String = baseURL) -> HTTPRequestSpec {
        request("PUT", wellnessBulkURL(base: base), apiKey, bulkBody(values))
    }

    private static func request(_ method: String, _ url: String, _ key: ApiKey, _ body: String) -> HTTPRequestSpec {
        HTTPRequestSpec(method: method, url: url,
                        headers: ["Authorization": key.authorizationHeader, "Content-Type": "application/json", "Accept": "application/json"],
                        body: Data(body.utf8))
    }

    // Swift prints doubles in shortest round-trip form ("68.4", "61.0"), like Kotlin.
    private static func fields(_ v: WellnessValues) -> String {
        #""restingHR":\#(v.restingHr),"hrv":\#(v.hrvRmssd),"hrvSDNN":\#(v.hrvSdnn)"#
    }
}
