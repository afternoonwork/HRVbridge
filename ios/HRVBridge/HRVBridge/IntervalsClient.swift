import Foundation
import HRVCore

/// Sends HRVCore request specs with URLSession. Logs method, path and status only.
struct IntervalsClient {
    let log: DebugLog
    var session: URLSession = .shared

    func send(_ spec: HTTPRequestSpec) async -> ApiResult {
        guard let url = URL(string: spec.url) else { return .networkError(message: "bad URL") }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = spec.method
        for (k, v) in spec.headers { request.setValue(v, forHTTPHeaderField: k) }
        request.httpBody = spec.body
        do {
            let (data, response) = try await session.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let body = String(decoding: data, as: UTF8.self)
            log.add("HTTP", "\(spec.logDescription) -> \(code)" + (code >= 400 ? " response=\(body.prefix(300))" : ""))
            return ApiResult(statusCode: code, body: body)
        } catch {
            log.add("HTTP", "\(spec.logDescription) -> network error: \(error.localizedDescription)")
            return .networkError(message: error.localizedDescription)
        }
    }
}
