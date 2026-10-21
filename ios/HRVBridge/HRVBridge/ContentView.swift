import HRVCore
import SwiftUI
import UIKit

struct ContentView: View {
    @State private var model = AppModel()

    var body: some View {
        NavigationStack {
            List {
                StrapSection(central: model.central)
                if model.central.isConnected {
                    LiveSection(central: model.central)
                    RecorderSection(model: model)
                }
                Section {
                    NavigationLink("Debug log") { DebugLogView(log: model.log) }
                }
            }
            .navigationTitle("HRV Bridge")
        }
    }
}

/// Owns the strap, the recorder and the debug log, and wires them together.
@Observable
final class AppModel {
    let log: DebugLog
    let central: HeartRateCentral
    let recorder = RawRecorder()

    init() {
        let epoch = Clock.nowMs()
        log = DebugLog(clockMs: { Clock.nowMs() - epoch })
        central = HeartRateCentral(log: log)
        central.onPacket = { [weak self] bytes, t in self?.recorder.add(bytes, at: t) }
        central.onDisconnected = { [weak self] in self?.recorder.stop() }
    }

    func startRecording() {
        UIApplication.shared.isIdleTimerDisabled = true
        recorder.start(deviceName: central.connectedName, deviceId: central.connectedId)
        log.add("REC", "raw recording started")
    }

    func recordingFinished() {
        UIApplication.shared.isIdleTimerDisabled = false
        log.add("REC", "raw recording finished, \(recorder.packetCount) packets")
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
    let log: DebugLog
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
        .onAppear { text = log.text() }
    }
}

#Preview {
    ContentView()
}
