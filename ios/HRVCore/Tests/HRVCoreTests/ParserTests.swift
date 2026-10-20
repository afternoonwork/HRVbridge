@testable import HRVCore
import XCTest

final class ParserTests: XCTestCase {
    func testSharedVectors() {
        XCTAssertFalse(TestVectors.shared.packets.isEmpty)
        for p in TestVectors.shared.packets {
            let m = HeartRateParser.parse(hexBytes(p.hex))
            guard let e = p.expected else {
                XCTAssertNil(m, p.name)
                continue
            }
            guard let m = m else { return XCTFail("nil for \(p.name)") }
            XCTAssertEqual(m.heartRate, e.heartRate, p.name)
            XCTAssertEqual(m.contactSupported, e.contactSupported, p.name)
            XCTAssertEqual(m.contactDetected, e.contactDetected, p.name)
            XCTAssertEqual(m.energyExpended, e.energyExpended, p.name)
            XCTAssertEqual(m.rrPresent, e.rrPresent, p.name)
            XCTAssertEqual(m.rrIntervalsMs, e.rrMs, p.name)
            XCTAssertEqual(m.droppedTrailingByte, e.droppedTrailingByte, p.name)
        }
    }

    func testUnsignedBytesAndExactConversion() {
        let m = HeartRateParser.parse([0x10, 0xC8, 0xFF, 0xFF])!
        XCTAssertEqual(m.heartRate, 200)
        XCTAssertEqual(m.rrIntervalsMs, [65535 * 1000.0 / 1024.0])
        XCTAssertEqual(HeartRateParser.parse([0x10, 60, 0xFF, 0x03])!.rrIntervalsMs, [999.0234375])
    }

    func testPoorContactOnlyWhenSupportedAndNotDetected() {
        XCTAssertTrue(HeartRateParser.parse([0x04, 60])!.poorContact)
        XCTAssertFalse(HeartRateParser.parse([0x06, 60])!.poorContact)
        XCTAssertFalse(HeartRateParser.parse([0x00, 60])!.poorContact)
    }

    func testEncoderRoundTrip() {
        let bytes = HeartRatePacketEncoder.encode(heartRate: 60, rrRaw: [1024, 512])
        XCTAssertEqual(bytes, [0x16, 60, 0x00, 0x04, 0x00, 0x02])
        XCTAssertEqual(HeartRateParser.parse(bytes)!.rrIntervalsMs, [1000.0, 500.0])
        XCTAssertEqual(HeartRateParser.parse(HeartRatePacketEncoder.encode(heartRate: 300, rrRaw: [], rrPresent: false))!.heartRate, 300)
        XCTAssertEqual(HeartRatePacketEncoder.msToRaw(600.0), 614)
    }

    func testSimulatedStreamGroupsBeatsPerSecond() {
        let packets = SimulatedStream.fromRr(Array(repeating: 600.0, count: 4))
        XCTAssertEqual(packets.map(\.timeMs), [1000, 2000, 3000])
        XCTAssertEqual(packets.map { HeartRateParser.parse($0.bytes)!.rrIntervalsMs.count }, [1, 2, 1])
        let rr = TestVectors.series(named: "clean 70 beats").rrMs
        XCTAssertEqual(SimulatedStream.fromRr(rr).flatMap { HeartRateParser.parse($0.bytes)!.rrIntervalsMs }, rr)
    }
}
