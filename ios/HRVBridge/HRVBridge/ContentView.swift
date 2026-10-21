import HRVCore
import SwiftUI
import UIKit

/// Connect screen while no key is saved (and until "Continue" after saving), else the main screen.
struct ContentView: View {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if model.measurement.apiKey == nil || model.showConnect {
                ConnectView(model: model)
            } else {
                NavigationStack { MainView(model: model) }
            }
        }
        .tint(Theme.ink)
        .onChange(of: scenePhase) { _, phase in
            // Foreground only: no background Bluetooth.
            if phase == .background { model.measurement.onBackgrounded() }
        }
    }
}

/// Owns the strap, the measurement, the raw recorder and the debug log, and wires them together.
@Observable
final class AppModel {
    let log: DebugLog
    let central: HeartRateCentral
    let measurement: MeasurementController
    let recorder = RawRecorder()
    /// Keeps the connect screen up after "Save key" until the user taps "Continue".
    var showConnect: Bool

    init() {
        let epoch = Clock.nowMs()
        let log = DebugLog(clockMs: { Clock.nowMs() - epoch })
        self.log = log
        central = HeartRateCentral(log: log)
        measurement = MeasurementController(log: log)
        showConnect = measurement.apiKey == nil
        central.onPacket = { [weak self] bytes, t in
            self?.recorder.add(bytes, at: t)
            self?.measurement.onPacket(bytes, at: t)
        }
        central.onDisconnected = { [weak self] in
            self?.recorder.stop()
            self?.measurement.onStrapDisconnected()
        }
    }

    func disconnectIntervals() {
        measurement.disconnectIntervals()
        showConnect = true
    }

    func startRecording() {
        UIApplication.shared.isIdleTimerDisabled = true
        recorder.start(deviceName: central.connectedName, deviceId: central.connectedId)
        log.add("REC", "raw recording started")
    }

    func recordingFinished() {
        if !measurement.isActive { UIApplication.shared.isIdleTimerDisabled = false }
        log.add("REC", "raw recording finished, \(recorder.packetCount) packets")
    }

    /// "Delete all measurements": session files, raw recordings and the debug log.
    /// The API key and Intervals.icu data are not touched.
    func deleteAllMeasurements() -> Bool {
        guard measurement.deleteAllMeasurements() else { return false }
        recorder.deleteFiles()
        log.clear()
        return true
    }

    /// Debug log plus the last session's JSON. Secrets are redacted by DebugLog.
    func shareText() -> String {
        var text = log.text()
        if let json = measurement.lastRecordJSON { text += "\n\nSession JSON:\n" + json }
        return text
    }

    /// A new measurement with the same strap, or a new search if it is gone.
    func measureAgain() {
        if central.isConnected {
            measurement.start()
        } else {
            measurement.cancel()
            central.startScan()
        }
    }

    // MARK: Main screen state

    var mainState: MainScreenState {
        var s = MainScreenState(phase: strapPhase)
        if let hr = central.latest?.heartRate, hr > 0 { s.heartRate = hr }
        if let clock = measurement.phaseClock {
            s.handClock = clock
            s.handAngle = clock.angle(at: Clock.nowMs())
            if clock.turn == 1 { s.pulse = measurement.pulse }
        }
        s.settlingSeconds = measurement.stabilisationSeconds
        s.strapConnected = central.isConnected
        s.cancelled = measurement.cancelledInBackground
        s.retryLine = retryLine
        if let session = measurement.state { s.phase = phase(session) }
        return s
    }

    private var strapPhase: MainPhase {
        switch central.status {
        case .idle, .disconnected: return .noStrap
        case let .bluetoothUnavailable(problem): return .bluetooth(problem)
        case .scanning: return .searching(central.found)
        case let .connecting(name): return .connecting(name)
        case let .connected(name): return .connected(name)
        case let .couldNotConnect(name): return .couldNotConnect(name)
        }
    }

    private func phase(_ state: SessionState) -> MainPhase {
        /// Rounded up, so "1 s" shows until the phase ends.
        func seconds(_ ms: Int64) -> Int { Int(max(0, (ms + 999) / 1000)) }
        switch state {
        case let .stabilising(remaining, poor):
            return .settling(secondsLeft: seconds(remaining), poorContact: poor)
        case let .measuring(remaining, beats, poor):
            return .measuring(secondsLeft: seconds(remaining), beats: beats, poorContact: poor)
        case let .finished(rr, _, result):
            guard let r = result else { return .notEnoughBeats }
            let summary = ResultSummary(restingHr: r.restingHr, rmssd: r.rmssdRounded, sdnn: r.sdnnRounded,
                                        accepted: r.acceptedBeats, total: rr.count, reliable: r.reliable,
                                        upload: measurement.upload)
            return measurement.upload == .awaitingAnswers ? .questions(summary) : .result(summary)
        case let .failed(reason):
            return .failed(reason)
        }
    }

    private var retryLine: String? {
        switch measurement.retry {
        case .none: return nil
        case .inProgress: return "Uploading earlier measurements…"
        case let .uploaded(n): return n == 1 ? "Uploaded 1 earlier measurement." : "Uploaded \(n) earlier measurements."
        case let .failed(reconnect):
            return reconnect ? "Intervals.icu did not accept the stored key. Please reconnect."
                             : "Earlier measurements could not be uploaded yet. The app will try again next time it opens."
        }
    }
}

/// The live main screen: state, actions, Stop confirmation, haptics and announcements.
struct MainView: View {
    let model: AppModel
    @State private var confirmStop = false
    @State private var dialLabel = ""
    @State private var dialLabelAt = Date.distantPast

    var body: some View {
        let state = model.mainState
        MainScreenView(state: state, dialAccessibility: dialLabel, actions: actions)
            .navigationTitle(Theme.appName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.bg, for: .navigationBar)
            .toolbar {
                if !state.isMeasuring {
                    ToolbarItem(placement: .topBarTrailing) {
                        NavigationLink { SettingsView(model: model) } label: {
                            Image(systemName: "slider.horizontal.3")
                        }
                        .accessibilityLabel("Settings")
                    }
                }
            }
            .alert("Stop the measurement?", isPresented: $confirmStop) {
                Button("Stop", role: .destructive) { model.measurement.cancel() }
                Button("Keep going", role: .cancel) {}
            } message: {
                Text("Nothing is saved or uploaded.")
            }
            .sensoryFeedback(trigger: state.strapConnected) { old, new in !old && new ? .success : nil }
            .sensoryFeedback(trigger: state.poorContact) { old, new in !old && new ? .warning : nil }
            .sensoryFeedback(trigger: state.milestone) { old, new in
                guard let new, new != old else { return nil }
                switch new {
                case .recording: return .impact(weight: .medium)
                case .finishedReliable, .uploaded: return .success
                case .finishedUnreliable: return .warning
                case .problem: return .error
                }
            }
            .onChange(of: state.phaseTitle) { _, title in
                announce(title)
                dialLabel = state.dialAccessibility
                dialLabelAt = Date()
            }
            .onChange(of: state.warning) { _, warning in
                if let warning { announce(warning) }
            }
            .onChange(of: state.dialAccessibility, initial: true) { _, label in
                // VoiceOver value at most every 5 s; phase changes update it at once (above).
                if Date().timeIntervalSince(dialLabelAt) >= 5 {
                    dialLabel = label
                    dialLabelAt = Date()
                }
            }
    }

    private var actions: MainActions {
        MainActions(
            findStrap: { model.central.startScan() },
            connect: { model.central.connect($0) },
            start: { model.measurement.start() },
            stop: { confirmStop = true },
            done: { model.measurement.cancel() },
            again: { model.measureAgain() },
            upload: { model.measurement.submitUpload() },
            submitAnswers: { model.measurement.submitAnswers($0) },
            openSettings: {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            })
    }

    private func announce(_ text: String) {
        AccessibilityNotification.Announcement(text).post()
    }
}

/// Snapshot of the debug log taken when the page opens (the log itself is not observable).
struct DebugLogView: View {
    let model: AppModel
    @State private var text = ""

    var body: some View {
        ScrollView {
            Text(text)
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle("Debug log")
        .toolbar {
            ShareLink(item: text) { Label("Share", systemImage: "square.and.arrow.up") }
        }
        .onAppear { text = model.shareText() }
    }
}

#Preview {
    ContentView()
}
