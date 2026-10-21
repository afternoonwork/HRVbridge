import Foundation

enum ISO {
    static func string(_ d: Date, fractional: Bool) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = fractional ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
        return f.string(from: d)
    }

    static func date(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

/// One measurement as stored on the device. Same JSON keys as Android's SessionRecord,
/// so files from both platforms can be read by the same tools.
public struct SessionRecord: Sendable {
    public var startedAt: Date
    public var date: LocalDate
    public var config: SessionConfig
    public var series: FilteredSeries
    public var result: HrvResult?
    public var malformedPackets: Int
    public var uploadedAt: Date?
    /// The user chose "Upload anyway" for an unreliable result; retried like a reliable one.
    public var uploadOverride: Bool

    public init(startedAt: Date, date: LocalDate, config: SessionConfig, series: FilteredSeries, result: HrvResult?,
                malformedPackets: Int, uploadedAt: Date?, uploadOverride: Bool = false) {
        self.startedAt = startedAt
        self.date = date
        self.config = config
        self.series = series
        self.result = result
        self.malformedPackets = malformedPackets
        self.uploadedAt = uploadedAt
        self.uploadOverride = uploadOverride
    }

    public var fileName: String {
        "session-" + ISO.string(startedAt, fractional: false).replacingOccurrences(of: ":", with: "-") + ".json"
    }

    /// Reliable, or explicitly overridden, and never uploaded.
    public var isPending: Bool {
        guard let r = result else { return false }
        return uploadedAt == nil && (r.reliable || uploadOverride)
    }

    public func jsonData() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return try e.encode(DTO(self))
    }

    public init(jsonData: Data) throws {
        let dto = try JSONDecoder().decode(DTO.self, from: jsonData)
        guard dto.version == 1, let started = ISO.date(dto.startedAt) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "unsupported session record"))
        }
        var config = SessionConfig(checkMs: dto.config.checkMs, stabilisationMs: dto.config.stabilisationMs,
                                   measurementMs: dto.config.measurementMs)
        config.filter.minRrMs = dto.config.minRrMs
        config.filter.maxRrMs = dto.config.maxRrMs
        config.filter.maxRelativeDeviation = dto.config.maxRelativeDeviation
        config.filter.medianWindow = dto.config.medianWindow
        config.gate.maxRemovedFraction = dto.config.maxRemovedFraction
        config.gate.minValidBeats = dto.config.minValidBeats
        self.init(
            startedAt: started,
            date: dto.date,
            config: config,
            series: FilteredSeries(rrMs: dto.rrMs, accepted: dto.accepted, rejectReasons: dto.rejectReasons),
            result: dto.result.map {
                HrvResult(meanNnMs: $0.meanNnMs, restingHr: $0.restingHr, rmssdMs: $0.rmssdMs, sdnnMs: $0.sdnnMs,
                          acceptedBeats: $0.acceptedBeats, removedBeats: $0.removedBeats, validPairs: $0.validPairs, reliable: $0.reliable)
            },
            malformedPackets: dto.malformedPackets,
            uploadedAt: dto.uploadedAt.flatMap(ISO.date),
            uploadOverride: dto.uploadOverride ?? false
        )
    }

    private struct DTO: Codable {
        struct Config: Codable {
            var checkMs: Int64, stabilisationMs: Int64, measurementMs: Int64
            var minRrMs: Double, maxRrMs: Double, maxRelativeDeviation: Double, medianWindow: Int
            var maxRemovedFraction: Double, minValidBeats: Int
        }

        struct Result: Codable {
            var meanNnMs: Double, restingHr: Int, rmssdMs: Double, sdnnMs: Double
            var rmssdRounded: Double, sdnnRounded: Double
            var acceptedBeats: Int, removedBeats: Int, validPairs: Int, reliable: Bool
        }

        var version: Int
        var startedAt: String
        var date: LocalDate
        var config: Config
        var malformedPackets: Int
        var rrMs: [Double]
        var accepted: [Bool]
        var rejectReasons: [RejectReason?]
        var result: Result?
        var uploadedAt: String?
        var uploadOverride: Bool?

        init(_ r: SessionRecord) {
            version = 1
            startedAt = ISO.string(r.startedAt, fractional: true)
            date = r.date
            let c = r.config
            config = Config(checkMs: c.checkMs, stabilisationMs: c.stabilisationMs, measurementMs: c.measurementMs,
                            minRrMs: c.filter.minRrMs, maxRrMs: c.filter.maxRrMs, maxRelativeDeviation: c.filter.maxRelativeDeviation,
                            medianWindow: c.filter.medianWindow, maxRemovedFraction: c.gate.maxRemovedFraction,
                            minValidBeats: c.gate.minValidBeats)
            malformedPackets = r.malformedPackets
            rrMs = r.series.rrMs
            accepted = r.series.accepted
            rejectReasons = r.series.rejectReasons
            result = r.result.map {
                Result(meanNnMs: $0.meanNnMs, restingHr: $0.restingHr, rmssdMs: $0.rmssdMs, sdnnMs: $0.sdnnMs,
                       rmssdRounded: $0.rmssdRounded, sdnnRounded: $0.sdnnRounded, acceptedBeats: $0.acceptedBeats,
                       removedBeats: $0.removedBeats, validPairs: $0.validPairs, reliable: $0.reliable)
            }
            uploadedAt = r.uploadedAt.map { ISO.string($0, fractional: true) }
            uploadOverride = r.uploadOverride
        }

        // Write explicit nulls like Android does, so the files look the same.
        func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(version, forKey: .version)
            try c.encode(startedAt, forKey: .startedAt)
            try c.encode(date, forKey: .date)
            try c.encode(config, forKey: .config)
            try c.encode(malformedPackets, forKey: .malformedPackets)
            try c.encode(rrMs, forKey: .rrMs)
            try c.encode(accepted, forKey: .accepted)
            try c.encode(rejectReasons, forKey: .rejectReasons)
            try c.encode(result, forKey: .result)
            try c.encode(uploadedAt, forKey: .uploadedAt)
            try c.encode(uploadOverride, forKey: .uploadOverride)
        }
    }
}

extension SessionRecord: Equatable {
    /// Times compare to the millisecond (they are stored as ISO strings with milliseconds).
    public static func == (a: SessionRecord, b: SessionRecord) -> Bool {
        func same(_ x: Date?, _ y: Date?) -> Bool {
            switch (x, y) {
            case (nil, nil): return true
            case let (x?, y?): return abs(x.timeIntervalSince(y)) < 0.001
            default: return false
            }
        }
        return same(a.startedAt, b.startedAt) && a.date == b.date && a.config == b.config && a.series == b.series
            && a.result == b.result && a.malformedPackets == b.malformedPackets && same(a.uploadedAt, b.uploadedAt)
            && a.uploadOverride == b.uploadOverride
    }
}

/// One JSON file per session in `directory`, written atomically.
public struct SessionStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public func save(_ record: SessionRecord) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try record.jsonData().write(to: directory.appendingPathComponent(record.fileName), options: .atomic)
    }

    /// Newest first; unreadable files are skipped.
    public func list() -> [SessionRecord] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.hasPrefix("session-") && $0.hasSuffix(".json") }
            .compactMap { try? SessionRecord(jsonData: Data(contentsOf: directory.appendingPathComponent($0))) }
            .sorted { $0.startedAt > $1.startedAt }
    }

    public func pendingUploads() -> [SessionRecord] { list().filter(\.isPending) }

    /// For each date only the newest session, and only if that one is pending, so an
    /// older session never overwrites a newer value of the same day. Newest date first.
    public func retryBatch() -> [SessionRecord] {
        Dictionary(grouping: list(), by: \.date).values
            .compactMap { $0.max { $0.startedAt < $1.startedAt } }
            .filter(\.isPending)
            .sorted { $0.date > $1.date }
    }
}

/// Raw 0x2A37 notifications with arrival times, for turning real strap data into test vectors.
public struct RawRecording: Codable, Equatable, Sendable {
    public struct Packet: Codable, Equatable, Sendable {
        /// ms since the recording started.
        public var tMs: Int64
        public var hex: String

        public var bytes: [UInt8] {
            var out: [UInt8] = []
            var i = hex.startIndex
            while i < hex.endIndex, let j = hex.index(i, offsetBy: 2, limitedBy: hex.endIndex) {
                if let b = UInt8(hex[i..<j], radix: 16) { out.append(b) }
                i = j
            }
            return out
        }
    }

    public var version = 1
    public var startedAt: String
    public var deviceName: String?
    public var deviceId: String?
    public var packets: [Packet] = []

    public init(startedAt: Date, deviceName: String?, deviceId: String?) {
        self.startedAt = ISO.string(startedAt, fractional: true)
        self.deviceName = deviceName
        self.deviceId = deviceId
    }

    public mutating func add(bytes: [UInt8], timeMs: Int64) {
        packets.append(Packet(tMs: timeMs, hex: bytes.map { String(format: "%02X", $0) }.joined()))
    }

    public func jsonData() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .prettyPrinted]
        return try e.encode(self)
    }

    public init(jsonData: Data) throws {
        self = try JSONDecoder().decode(RawRecording.self, from: jsonData)
    }
}
