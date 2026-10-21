import Foundation
import HRVCore
import Observation
import UIKit

enum UploadStatus: Equatable {
    case none
    case inProgress
    case uploaded
    case notConnected
    case reconnectNeeded
    case failed(String)
}

enum RetryStatus: Equatable {
    case none
    case inProgress
    case uploaded(Int)
    case failed(reconnect: Bool)
}

/// The measurement flow (same spec as Android): signal check, stabilisation, 60 s
/// measurement, local storage, upload with the API key, retry of pending sessions.
@Observable
final class MeasurementController {
    private(set) var state: SessionState?
    private(set) var upload: UploadStatus = .none
    private(set) var retry: RetryStatus = .none
    private(set) var apiKey: ApiKey?
    private(set) var cancelledInBackground = false

    /// 0, 30, 60 or 90 s; remembered across launches.
    private(set) var stabilisationSeconds: Int

    func setStabilisation(_ seconds: Int) {
        stabilisationSeconds = seconds
        UserDefaults.standard.set(seconds, forKey: "stabilisationSeconds")
    }

    var isActive: Bool { state?.isActive ?? false }

    private let log: DebugLog
    private let client: IntervalsClient
    private let store: SessionStore
    @ObservationIgnored private var session: MeasurementSession?
    @ObservationIgnored private var record: SessionRecord?
    @ObservationIgnored private var startedAt = Date()
    @ObservationIgnored private var ticker: Task<Void, Never>?

    init(log: DebugLog) {
        self.log = log
        client = IntervalsClient(log: log)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        store = SessionStore(directory: support.appendingPathComponent("sessions"))
        let saved = UserDefaults.standard.object(forKey: "stabilisationSeconds") as? Int
        stabilisationSeconds = saved ?? 60
        apiKey = KeychainStore.load()
        if let k = apiKey { registerSecrets(k) }
        retryPending()
    }

    // MARK: Connection

    enum SaveKeyResult { case saved, blank, storageFailed }

    func saveApiKey(_ text: String) -> SaveKeyResult {
        guard let key = ApiKey(userInput: text) else { return .blank }
        registerSecrets(key)
        guard KeychainStore.save(key) else {
            log.add("AUTH", "could not store the key in the Keychain")
            return .storageFailed
        }
        apiKey = key
        log.add("AUTH", "API key saved")
        retryPending()
        return .saved
    }

    func disconnectIntervals() {
        KeychainStore.delete()
        apiKey = nil
        log.add("AUTH", "API key removed")
    }

    private func registerSecrets(_ key: ApiKey) {
        log.addSecret(key.key)
        log.addSecret(String(key.authorizationHeader.dropFirst("Basic ".count)))
    }

    // MARK: Measurement

    /// Starts once the strap is connected and notifying; the 10 s check starts now.
    func start() {
        cancel()
        cancelledInBackground = false
        upload = .none
        record = nil
        startedAt = Date()
        let config = SessionConfig(stabilisationMs: Int64(stabilisationSeconds) * 1000)
        log.add("SESSION", "start \(startedAt), stabilisation \(stabilisationSeconds) s")
        let s = MeasurementSession(config: config, startMs: Clock.nowMs())
        session = s
        publish()
        UIApplication.shared.isIdleTimerDisabled = true
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(200))
                self?.tick()
            }
        }
    }

    func onPacket(_ bytes: [UInt8], at t: Int64) {
        guard let s = session else { return }
        s.onPacket(bytes, timeMs: t)
        publish()
    }

    func onStrapDisconnected() {
        guard let s = session else { return }
        s.onDisconnected()
        publish()
    }

    /// Back to the start screen (also used for "Cancel" and "Done").
    func cancel() {
        ticker?.cancel()
        ticker = nil
        session = nil
        state = nil
        upload = .none
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// Foreground only: leaving the app abandons a running measurement.
    func onBackgrounded() {
        guard isActive else { return }
        log.add("SESSION", "cancelled: app left the foreground")
        cancel()
        cancelledInBackground = true
    }

    /// User override for an unreliable measurement.
    func uploadAnyway() {
        guard var r = record, r.result != nil, upload == .none else { return }
        log.add("UPLOAD", "user override for unreliable measurement")
        r.uploadOverride = true
        save(r)
        startUpload(r)
    }

    /// Deletes all local session files. Keeps the API key; never touches Intervals.icu.
    /// Refused while a measurement runs.
    func deleteAllMeasurements() -> Bool {
        guard !isActive else { return false }
        cancel()
        record = nil
        retry = .none
        cancelledInBackground = false
        store.deleteAll()
        return true
    }

    /// The last finished session as JSON, for the debug log share.
    var lastRecordJSON: String? {
        record.flatMap { try? $0.jsonData() }.map { String(decoding: $0, as: UTF8.self) }
    }

    private func tick() {
        guard let s = session else { return }
        s.onTick(Clock.nowMs())
        publish()
    }

    private func publish() {
        guard let s = session else { return }
        let new = s.state
        if new == state { return }
        if !sameCase(new, state) { log.add("PHASE", summary(new)) }
        state = new
        switch new {
        case let .finished(_, series, result):
            stopTicking()
            // The wellness record is the local day the measurement started, not ended.
            let r = SessionRecord(startedAt: startedAt, date: LocalDate(date: startedAt),
                                  config: SessionConfig(stabilisationMs: Int64(stabilisationSeconds) * 1000),
                                  series: series, result: result, malformedPackets: s.malformedPackets, uploadedAt: nil)
            record = r
            save(r)
            log.add("RESULT", result.map { "\($0)" } ?? "no result")
            if result?.reliable == true { startUpload(r) }
        case .failed:
            stopTicking()
        default:
            break
        }
    }

    private func stopTicking() {
        ticker?.cancel()
        ticker = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func startUpload(_ r: SessionRecord) {
        guard let result = r.result else { return }
        guard let key = apiKey else {
            upload = .notConnected
            log.add("UPLOAD", "not connected, session stays pending")
            return
        }
        upload = .inProgress
        let spec = Intervals.putWellness(WellnessValues(date: r.date, result: result), apiKey: key)
        Task { [weak self] in
            guard let self else { return }
            let res = await self.client.send(spec)
            self.log.add("UPLOAD", "\(res)")
            switch res {
            case .success:
                var done = self.record ?? r
                done.uploadedAt = Date()
                self.record = done
                self.save(done)
                self.upload = .uploaded
            case .unauthorised:
                self.upload = .reconnectNeeded
            case let .httpError(code, _):
                self.upload = .failed("HTTP \(code)")
            case let .networkError(message):
                self.upload = .failed(message)
            }
        }
    }

    // MARK: Retry

    /// Newest pending session per date, in one bulk PUT. Runs on launch and after saving a key.
    func retryPending() {
        guard let key = apiKey, retry != .inProgress else { return }
        let batch = store.retryBatch()
        guard !batch.isEmpty else { return }
        retry = .inProgress
        log.add("RETRY", "\(batch.count) pending: \(batch.map { $0.date.description }.joined(separator: ", "))")
        let values = batch.compactMap { r in r.result.map { WellnessValues(date: r.date, result: $0) } }
        let spec = Intervals.putWellnessBulk(values, apiKey: key)
        Task { [weak self] in
            guard let self else { return }
            let res = await self.client.send(spec)
            self.log.add("RETRY", "\(res)")
            switch res {
            case .success:
                let now = Date()
                for var r in batch {
                    r.uploadedAt = now
                    self.save(r)
                }
                self.retry = .uploaded(batch.count)
            case .unauthorised:
                self.retry = .failed(reconnect: true)
            default:
                self.retry = .failed(reconnect: false)
            }
        }
    }

    private func save(_ r: SessionRecord) {
        do {
            try store.save(r)
        } catch {
            log.add("STORE", "save failed: \(error)")
        }
    }

    private func sameCase(_ a: SessionState?, _ b: SessionState?) -> Bool {
        switch (a, b) {
        case (.checking?, .checking?), (.stabilising?, .stabilising?), (.measuring?, .measuring?),
             (.finished?, .finished?), (.failed?, .failed?): return true
        default: return false
        }
    }

    private func summary(_ s: SessionState) -> String {
        switch s {
        case let .finished(rr, _, _): return "finished (\(rr.count) RR)"
        default: return "\(s)"
        }
    }
}
