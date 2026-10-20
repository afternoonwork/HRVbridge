import Foundation
import HRVCore
import Observation

/// Milestone 1: records raw 0x2A37 notifications with timestamps for a fixed time and
/// writes them as JSON (HRVCore `RawRecording`), to share and turn into test vectors.
@Observable
final class RawRecorder {
    static let durationMs: Int64 = 120_000

    private(set) var isRecording = false
    private(set) var elapsedMs: Int64 = 0
    private(set) var packetCount = 0
    /// The finished file, ready for the share sheet.
    private(set) var fileURL: URL?
    private(set) var error: String?

    @ObservationIgnored private var recording: RawRecording?
    @ObservationIgnored private var startMs: Int64 = 0
    @ObservationIgnored private var ticker: Task<Void, Never>?

    func start(deviceName: String?, deviceId: UUID?) {
        recording = RawRecording(startedAt: Date(), deviceName: deviceName, deviceId: deviceId?.uuidString)
        startMs = Clock.nowMs()
        elapsedMs = 0
        packetCount = 0
        fileURL = nil
        error = nil
        isRecording = true
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                self?.tick()
            }
        }
    }

    func add(_ bytes: [UInt8], at t: Int64) {
        guard isRecording else { return }
        recording?.add(bytes: bytes, timeMs: t - startMs)
        packetCount += 1
    }

    /// Stops early (e.g. strap disconnected) and keeps what was recorded.
    func stop() {
        guard isRecording else { return }
        finish()
    }

    private func tick() {
        guard isRecording else { return }
        elapsedMs = Clock.nowMs() - startMs
        if elapsedMs >= Self.durationMs { finish() }
    }

    private func finish() {
        isRecording = false
        ticker?.cancel()
        ticker = nil
        guard let recording else { return }
        let stamp = recording.startedAt.replacingOccurrences(of: ":", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("hrvbridge-raw-\(stamp).json")
        do {
            try recording.jsonData().write(to: url, options: .atomic)
            fileURL = url
        } catch {
            self.error = "Could not save the recording: \(error.localizedDescription)"
        }
    }
}
