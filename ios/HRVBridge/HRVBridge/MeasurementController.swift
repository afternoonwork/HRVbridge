import Foundation
import HRVCore
import Observation
import UIKit

/// The running phase on the wall clock (`Clock.nowMs()`): the hand makes one turn over
/// settling (turn 0) and one over the measurement (turn 1), like a seconds hand.
struct PhaseClock: Equatable {
    var startMs: Int64
    var durationMs: Int64
    var turn: Int

    /// Cumulative degrees: settling 0 to 360, measuring 360 to 720.
    func angle(at t: Int64) -> Double {
        let f = durationMs > 0 ? Double(t - startMs) / Double(durationMs) : 1
        return Double(turn) * 360 + min(1, max(0, f)) * 360
    }
}

/// One accepted beat while measuring: `id` changes per beat, `rrMs` is its length.
struct BeatPulse: Equatable {
    var id = 0
    var rrMs = 0.0
}

enum UploadStatus: Equatable {
    case none
    /// Morning questions are on: the result waits for answers (or Skip) before uploading.
    case awaitingAnswers
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
    /// The running phase on the wall clock, for the sweeping hand; nil when not measuring.
    private(set) var phaseClock: PhaseClock?
    /// Changes on every accepted beat while measuring (the ring breathes).
    private(set) var pulse = BeatPulse()
    /// Result of the check after "Save key"; nil while checking or before a save.
    private(set) var keyCheck: KeyCheck?
    private(set) var checkingKey = false

    /// 0, 30, 60 or 90 s; remembered across launches.
    private(set) var stabilisationSeconds: Int

    /// Optional morning questions after a result; off by default.
    private(set) var questionsEnabled = UserDefaults.standard.bool(forKey: "morningQuestions")

    func setQuestionsEnabled(_ on: Bool) {
        questionsEnabled = on
        UserDefaults.standard.set(on, forKey: "morningQuestions")
    }

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
    @ObservationIgnored private var config = SessionConfig()
    /// RR of the measuring phase so far, to tell accepted beats from rejected ones for the
    /// hand. The same filter as the result; the result itself still comes from the session.
    @ObservationIgnored private var measuringRr: [Double] = []

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
        checkKey(key)
        return .saved
    }

    /// One authenticated read (today's wellness record). Rejected: the key is removed again.
    /// Anything else keeps it; retry of pending sessions follows a valid or unchecked key.
    private func checkKey(_ key: ApiKey) {
        keyCheck = nil
        checkingKey = true
        let spec = Intervals.getWellness(LocalDate(date: Date()), apiKey: key)
        Task { [weak self] in
            guard let self else { return }
            let outcome = KeyCheck(await self.client.send(spec))
            self.log.add("AUTH", "key check: \(outcome)")
            self.checkingKey = false
            self.keyCheck = outcome
            if outcome == .rejected {
                KeychainStore.delete()
                self.apiKey = nil
            } else {
                self.retryPending()
            }
        }
    }

    func disconnectIntervals() {
        KeychainStore.delete()
        apiKey = nil
        keyCheck = nil
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
        config = SessionConfig(stabilisationMs: Int64(stabilisationSeconds) * 1000)
        measuringRr = []
        phaseClock = nil
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
        pulseBeats(bytes, state: s.state)
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
        measuringRr = []
        phaseClock = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// Foreground only: leaving the app abandons a running measurement.
    func onBackgrounded() {
        guard isActive else { return }
        log.add("SESSION", "cancelled: app left the foreground")
        cancel()
        cancelledInBackground = true
    }

    /// The user tapped Upload (or "Upload anyway"): only now is the session pending.
    func submitUpload() {
        guard var r = record, r.result != nil, upload == .none else { return }
        log.add("UPLOAD", r.result?.reliable == true ? "user submitted" : "user override for unreliable measurement")
        r.uploadOverride = true
        record = r
        save(r)
        uploadOrAsk(r)
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

    // MARK: Hand and beats

    /// Accepted beats while measuring make the orange ring breathe (same filter as the
    /// result; the result itself comes from the session).
    private func pulseBeats(_ bytes: [UInt8], state: SessionState) {
        guard case let .measuring(_, beats, _) = state,
              let rr = HeartRateParser.parse(bytes)?.rrIntervalsMs, !rr.isEmpty else { return }
        // The session restarts the measurement on poor contact before the check.
        if measuringRr.count + rr.count != beats { measuringRr = [] }
        let first = measuringRr.count
        measuringRr += rr
        let accepted = ArtefactFilter.filter(measuringRr, config: config.filter).accepted
        guard let last = accepted.indices.last(where: { $0 >= first && accepted[$0] }) else { return }
        pulse = BeatPulse(id: pulse.id + 1, rrMs: measuringRr[last])
    }

    /// The hand follows the wall clock: anchor the current phase on the session's
    /// remaining time (it holds while contact is poor before the check).
    private func updatePhaseClock(_ state: SessionState?) {
        let next: (remaining: Int64, duration: Int64, turn: Int)?
        switch state {
        case let .stabilising(remaining, _)?: next = (remaining, config.stabilisationMs, 0)
        case let .measuring(remaining, _, _)?: next = (remaining, config.measurementMs, 1)
        default: next = nil
        }
        guard let next else {
            if phaseClock != nil { phaseClock = nil }
            return
        }
        let start = Clock.nowMs() - (next.duration - next.remaining)
        if let c = phaseClock, c.turn == next.turn, abs(c.startMs - start) < 100 { return }
        phaseClock = PhaseClock(startMs: start, durationMs: next.duration, turn: next.turn)
    }

    private func tick() {
        guard let s = session else { return }
        s.onTick(Clock.nowMs())
        publish()
    }

    private func publish() {
        guard let s = session else { return }
        let new = s.state
        updatePhaseClock(new)
        if new == state { return }
        if !sameCase(new, state) { log.add("PHASE", summary(new)) }
        state = new
        switch new {
        case let .finished(_, series, result):
            stopTicking()
            // The wellness record is the local day the measurement started, not ended.
            let r = SessionRecord(startedAt: startedAt, date: LocalDate(date: startedAt),
                                  config: config,
                                  series: series, result: result, malformedPackets: s.malformedPackets, uploadedAt: nil)
            record = r
            save(r)
            log.add("RESULT", result.map { "\($0)" } ?? "no result")
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

    private func uploadOrAsk(_ r: SessionRecord) {
        if questionsEnabled {
            upload = .awaitingAnswers
        } else {
            startUpload(r)
        }
    }

    /// The morning questions page: answers (or nil for Skip), then everything uploads together.
    func submitAnswers(_ answers: ReadinessAnswers?) {
        guard upload == .awaitingAnswers, var r = record else { return }
        if let answers, !answers.isEmpty {
            r.answers = answers
            record = r
            save(r)
            log.add("UPLOAD", "with answers: \(answers)")
        }
        startUpload(r)
    }

    private func startUpload(_ r: SessionRecord) {
        guard let values = r.wellnessValues else { return }
        guard let key = apiKey else {
            upload = .notConnected
            log.add("UPLOAD", "not connected, session stays pending")
            return
        }
        upload = .inProgress
        let spec = Intervals.putWellness(values, apiKey: key)
        Task { [weak self] in
            guard let self else { return }
            var res = await self.client.send(spec)
            self.log.add("UPLOAD", "\(res)")
            // Sick day: a calendar event after the wellness values (idempotent per date).
            if case .success = res, r.answers?.sick == true {
                res = await self.client.send(Intervals.sickEvent(r.date, apiKey: key))
                self.log.add("UPLOAD", "sick event: \(res)")
            }
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
        let values = batch.compactMap(\.wellnessValues)
        let spec = Intervals.putWellnessBulk(values, apiKey: key)
        Task { [weak self] in
            guard let self else { return }
            let res = await self.client.send(spec)
            self.log.add("RETRY", "\(res)")
            switch res {
            case .success:
                let now = Date()
                var sickFailed = false
                for var r in batch {
                    // A sick day also needs its calendar event; until that is sent, stay pending.
                    if r.answers?.sick == true {
                        let event = await self.client.send(Intervals.sickEvent(r.date, apiKey: key))
                        self.log.add("RETRY", "sick event \(r.date): \(event)")
                        guard case .success = event else {
                            sickFailed = true
                            continue
                        }
                    }
                    r.uploadedAt = now
                    self.save(r)
                }
                self.retry = sickFailed ? .failed(reconnect: false) : .uploaded(batch.count)
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
        case (.stabilising?, .stabilising?), (.measuring?, .measuring?),
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
