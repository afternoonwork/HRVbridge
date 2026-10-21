import Foundation
import XCTest

/// The shared contract with the Android `:core` suite, read straight from the repo.
struct TestVectors: Decodable {
    struct Packet: Decodable {
        struct Expected: Decodable {
            let heartRate: Int
            let contactSupported: Bool
            let contactDetected: Bool
            let energyExpended: Int?
            let rrPresent: Bool
            let rrMs: [Double]
            let droppedTrailingByte: Bool
        }

        let name: String
        let hex: String
        let expected: Expected?
    }

    struct Series: Decodable {
        struct Metrics: Decodable {
            let meanNnMs: Double
            let restingHr: Int
            let rmssdMs: Double
            let sdnnMs: Double
            let rmssdRounded: Double
            let sdnnRounded: Double
            let acceptedBeats: Int
            let removedBeats: Int
            let validPairs: Int
            let reliable: Bool
        }

        struct Expected: Decodable {
            let accepted: [Bool]
            let rejectReasons: [String?]
            let metrics: Metrics?
        }

        let name: String
        let rrMs: [Double]
        let expected: Expected
    }

    struct RoundingCase: Decodable {
        let value: Double
        let decimals: Int
        let expected: Double
    }

    let version: Int
    let rounding: [RoundingCase]
    let packets: [Packet]
    let series: [Series]

    static let url: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // HRVCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // HRVCore
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repo root
        .appendingPathComponent("core/src/test/resources/test-vectors.json")

    static let shared: TestVectors = {
        do {
            return try JSONDecoder().decode(TestVectors.self, from: Data(contentsOf: url))
        } catch {
            fatalError("cannot load \(url.path): \(error)")
        }
    }()

    static func series(named name: String) -> Series {
        shared.series.first { $0.name == name }!
    }
}

func hexBytes(_ s: String) -> [UInt8] {
    var out: [UInt8] = []
    var i = s.startIndex
    while i < s.endIndex {
        let j = s.index(i, offsetBy: 2)
        out.append(UInt8(s[i..<j], radix: 16)!)
        i = j
    }
    return out
}

func XCTAssertClose(_ a: Double, _ b: Double, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertLessThan(abs(a - b), 1e-9, "\(message): \(a) vs \(b)", file: file, line: line)
}
