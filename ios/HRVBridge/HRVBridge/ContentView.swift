import HRVCore
import SwiftUI
import UIKit

struct ContentView: View {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                if model.measurement.apiKey == nil {
                    // Not connected yet: connecting comes first.
                    IntervalsSection(measurement: model.measurement)
                }
                StrapSection(central: model.central)
                MeasurementSection(measurement: model.measurement, strapConnected: model.central.isConnected)
                if model.measurement.apiKey != nil {
                    IntervalsSection(measurement: model.measurement)
                }
                if model.central.isConnected {
                    LiveSection(central: model.central)
                    RecorderSection(model: model)
                }
                Section {
                    NavigationLink("Debug log") { DebugLogView(model: model) }
                }
            }
            .navigationTitle("HRV Bridge")
        }
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

    init() {
        let epoch = Clock.nowMs()
        let log = DebugLog(clockMs: { Clock.nowMs() - epoch })
        self.log = log
        central = HeartRateCentral(log: log)
        measurement = MeasurementController(log: log)
        central.onPacket = { [weak self] bytes, t in
            self?.recorder.add(bytes, at: t)
            self?.measurement.onPacket(bytes, at: t)
        }
        central.onDisconnected = { [weak self] in
            self?.recorder.stop()
            self?.measurement.onStrapDisconnected()
        }
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

    /// Debug log plus the last session's JSON. Secrets are redacted by DebugLog.
    func shareText() -> String {
        var text = log.text()
        if let json = measurement.lastRecordJSON { text += "\n\nSession JSON:\n" + json }
        return text
    }
}

private struct IntervalsSection: View {
    let measurement: MeasurementController
    @State private var key = ""
    @State private var error: String?

    var body: some View {
        Section("Connect to Intervals.icu") {
            if measurement.apiKey != nil {
                Text("Connected with your personal API key.")
                Button("Disconnect", role: .destructive) { measurement.disconnectIntervals() }
            } else {
                Text("Paste your personal API key. You find it in Intervals.icu under Settings, Developer Settings.")
                Text("The key is stored in the Keychain on this phone only and is sent only to Intervals.icu.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SecureField("API key", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let error { Text(error).foregroundStyle(.red) }
                Button("Save key") {
                    switch measurement.saveApiKey(key) {
                    case .saved:
                        key = ""
                        error = nil
                    case .blank: error = "Please paste a key first."
                    case .storageFailed: error = "Couldn't store the key securely on this device."
                    }
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Keep your morning values intact").font(.subheadline.bold())
                Text("This app writes your resting HR and HRV (rMSSD, SDNN) to today's wellness record in Intervals.icu. If another service (e.g. Garmin, Oura, Whoop) also sends resting HR or HRV to Intervals.icu, it may overwrite these values. To prevent that, open Intervals.icu settings, and for each connected service untick Resting HR and HRV.")
                    .font(.footnote)
            }
        }
    }
}

private struct MeasurementSection: View {
    let measurement: MeasurementController
    let strapConnected: Bool

    var body: some View {
        Section("Morning measurement") {
            switch measurement.state {
            case nil:
                idle
            case let .checking(elapsed, poor):
                running("Checking the signal: \(seconds(elapsed)) s", poor)
            case let .stabilising(remaining, poor):
                running("Settling in. Lie still and breathe normally. \(seconds(remaining)) s", poor)
            case let .measuring(remaining, beats, poor):
                running("Measuring: \(seconds(remaining)) s left. Beats: \(beats)", poor)
            case let .finished(rr, _, result):
                finished(rrCount: rr.count, result: result)
            case let .failed(reason):
                Text(failText(reason)).foregroundStyle(.red)
                Button("Try again") { measurement.cancel() }
            }
        }
    }

    @ViewBuilder private var idle: some View {
        if measurement.cancelledInBackground {
            Text("The measurement was cancelled because the app left the screen. Keep it open while measuring.")
        }
        switch measurement.retry {
        case .none: EmptyView()
        case .inProgress: Text("Uploading earlier measurements…")
        case let .uploaded(n): Text(n == 1 ? "Uploaded 1 earlier measurement." : "Uploaded \(n) earlier measurements.")
        case let .failed(reconnect):
            Text(reconnect ? "Intervals.icu did not accept the stored key. Please reconnect."
                           : "Earlier measurements could not be uploaded yet. The app will try again next time it opens.")
                .foregroundStyle(.red)
        }
        Picker("Settling time", selection: Binding(get: { measurement.stabilisationSeconds },
                                                    set: { measurement.setStabilisation($0) })) {
            ForEach([0, 30, 60, 90], id: \.self) { Text("\($0) s").tag($0) }
        }
        Button("Start measurement") { measurement.start() }
            .disabled(!strapConnected)
        if !strapConnected { Text("Connect your strap first.").font(.footnote).foregroundStyle(.secondary) }
    }

    @ViewBuilder private func running(_ status: String, _ poorContact: Bool) -> some View {
        Text(status).font(.headline)
        if poorContact {
            Text("Poor contact. Moisten the electrodes and make sure the strap fits snugly.").foregroundStyle(.red)
        }
        Button("Cancel", role: .cancel) { measurement.cancel() }
    }

    @ViewBuilder private func finished(rrCount: Int, result: HrvResult?) -> some View {
        if let r = result {
            LabeledContent("Resting HR", value: "\(r.restingHr) bpm")
            LabeledContent("rMSSD", value: String(format: "%.1f ms", r.rmssdRounded))
            LabeledContent("SDNN", value: String(format: "%.1f ms", r.sdnnRounded))
            LabeledContent("Beats accepted", value: "\(r.acceptedBeats) of \(rrCount)")
            if !r.reliable {
                Text("Measurement unreliable, please repeat.").foregroundStyle(.red)
            }
            switch measurement.upload {
            case .none:
                if !r.reliable { Button("Upload anyway") { measurement.uploadAnyway() } }
            case .inProgress: Text("Uploading…")
            case .uploaded: Text("Uploaded.")
            case .notConnected: Text("Not uploaded: connect to Intervals.icu first. It will upload once you have saved your key.")
            case .reconnectNeeded: Text("Intervals.icu did not accept the stored key. Please reconnect.").foregroundStyle(.red)
            case let .failed(detail): Text("Upload failed (\(detail)). The app will try again next time it opens.").foregroundStyle(.red)
            }
        } else {
            Text("Not enough beats to calculate results. Please repeat.")
        }
        Button("Done") { measurement.cancel() }
    }

    /// Rounded up, so "1 s" shows until the phase ends.
    private func seconds(_ ms: Int64) -> Int64 { (ms + 999) / 1000 }

    private func failText(_ reason: FailReason) -> String {
        switch reason {
        case .noData: return "No data was received from the strap."
        case .noRR: return "This sensor does not send beat-to-beat data."
        case .implausible: return "The strap's beat-to-beat data does not match its heart rate. Check the strap and try again."
        case .disconnected: return "The strap disconnected."
        }
    }
}

private struct StrapSection: View {
    let central: HeartRateCentral

    var body: some View {
        Section("Strap") {
            switch central.status {
            case .idle:
                Text("Put the strap on and moisten the electrodes. Close Polar Flow and other apps that may be connected to it.")
                Button("Find strap") { central.startScan() }
            case let .bluetoothUnavailable(message):
                Text(message)
                Button("Try again") { central.startScan() }
            case .scanning:
                HStack {
                    ProgressView()
                    Text("Looking for heart rate straps…")
                }
                ForEach(central.found) { strap in
                    Button {
                        central.connect(strap.id)
                    } label: {
                        LabeledContent(strap.name, value: strap.rssi == 0 ? "" : "\(strap.rssi) dBm")
                    }
                }
            case let .connecting(name):
                HStack {
                    ProgressView()
                    Text("Connecting to \(name)…")
                }
            case let .connected(name):
                LabeledContent("Connected", value: name)
                Button("Disconnect", role: .destructive) { central.disconnect() }
            case let .disconnected(reason):
                Text("Not connected (\(reason)).")
                Button("Find strap") { central.startScan() }
            }
        }
    }
}

private struct LiveSection: View {
    let central: HeartRateCentral

    var body: some View {
        Section("Live") {
            LabeledContent("Heart rate", value: central.latest.map { "\($0.heartRate) bpm" } ?? "-")
            LabeledContent("Contact", value: contactText)
            LabeledContent("Beat-to-beat data", value: central.latest?.rrPresent == true ? "yes" : "not yet")
            LabeledContent("Last RR (ms)", value: central.recentRr.map { String(format: "%.0f", $0) }.joined(separator: " "))
            LabeledContent("Packets", value: "\(central.packetCount)")
        }
    }

    private var contactText: String {
        guard let m = central.latest else { return "-" }
        if !m.contactSupported { return "not reported" }
        return m.contactDetected ? "good" : "poor, moisten the electrodes"
    }
}

private struct RecorderSection: View {
    let model: AppModel

    var body: some View {
        let r = model.recorder
        Section("Raw recording (2 minutes)") {
            if r.isRecording {
                ProgressView(value: Double(r.elapsedMs), total: Double(RawRecorder.durationMs)) {
                    Text("Recording: \(max(0, (RawRecorder.durationMs - r.elapsedMs) / 1000)) s left, \(r.packetCount) packets")
                }
                Button("Stop early") { r.stop() }
            } else {
                Text("Records every notification from the strap exactly as received, for testing and support.")
                Button("Start recording") { model.startRecording() }
            }
            if let url = r.fileURL {
                ShareLink(item: url) { Label("Share recording (\(r.packetCount) packets)", systemImage: "square.and.arrow.up") }
            }
            if let e = r.error { Text(e).foregroundStyle(.red) }
        }
        .onChange(of: r.isRecording) { _, recording in
            if !recording { model.recordingFinished() }
        }
    }
}

/// Snapshot of the debug log taken when the page opens (the log itself is not observable).
private struct DebugLogView: View {
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
