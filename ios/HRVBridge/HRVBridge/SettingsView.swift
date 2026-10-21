import HRVCore
import SwiftUI

struct SettingsView: View {
    let model: AppModel
    @State private var confirmDelete = false
    @State private var deleteMessage: String?

    var body: some View {
        List {
            Section {
                Picker("Settling time", selection: Binding(get: { model.measurement.stabilisationSeconds },
                                                            set: { model.measurement.setStabilisation($0) })) {
                    ForEach([0, 30, 60, 90], id: \.self) { Text("\($0) s").tag($0) }
                }
            } header: {
                Text("Measurement")
            } footer: {
                Text("Time to lie still before the 60 s measurement. Beats from this time are not used.")
            }

            Section("Intervals.icu") {
                LabeledContent("API key", value: "Connected")
                Button("Disconnect", role: .destructive) { model.disconnectIntervals() }
            }

            Section {
                Button("Delete all measurements", role: .destructive) { confirmDelete = true }
                    .confirmationDialog("Delete all measurements?", isPresented: $confirmDelete, titleVisibility: .visible) {
                        Button("Delete", role: .destructive) {
                            deleteMessage = model.deleteAllMeasurements()
                                ? "All measurements on this phone were deleted."
                                : "Finish or cancel the running measurement first."
                        }
                    } message: {
                        Text("This deletes every measurement and raw recording stored on this phone and the debug log. Your API key stays saved, and nothing is deleted in Intervals.icu. Measurements not yet uploaded will be lost.")
                    }
                if let deleteMessage { Text(deleteMessage).font(.footnote) }
                NavigationLink("Debug log") { DebugLogView(model: model) }
            } header: {
                Text("Data")
            } footer: {
                Text("Measurements are stored on this phone only.")
            }

            #if DEBUG
            Section("Debug builds only") {
                NavigationLink("Simulated strap and raw recording") { DebugToolsView(model: model) }
            }
            #endif
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#if DEBUG
/// Debug-only tools in plain lists: simulated strap, live packet view, raw recording.
struct DebugToolsView: View {
    let model: AppModel
    @State private var scenario = SimulatorScenario.calm
    @State private var speed = 10

    var body: some View {
        let central = model.central
        List {
            Section("Simulated strap") {
                Picker("Scenario", selection: $scenario) {
                    ForEach(SimulatorScenario.allCases) { Text($0.title).tag($0) }
                }
                Picker("Speed", selection: $speed) {
                    ForEach([1, 10, 60], id: \.self) { Text("\($0)x").tag($0) }
                }
                .pickerStyle(.segmented)
                Button("Start simulated strap") { central.startSimulation(scenario, speed: Double(speed)) }
                    .disabled(central.isConnected)
                if central.isConnected {
                    Button("Disconnect strap", role: .destructive) { central.disconnect() }
                }
            }
            if central.isConnected {
                Section("Live") {
                    LabeledContent("Heart rate", value: central.latest.map { "\($0.heartRate) bpm" } ?? "none")
                    LabeledContent("Contact", value: contactText)
                    LabeledContent("Beat-to-beat data", value: central.latest?.rrPresent == true ? "yes" : "not yet")
                    LabeledContent("Last RR (ms)", value: central.recentRr.map { String(format: "%.0f", $0) }.joined(separator: " "))
                    LabeledContent("Packets", value: "\(central.packetCount)")
                }
                RecorderSection(model: model)
            }
        }
        .navigationTitle("Debug tools")
    }

    private var contactText: String {
        guard let m = model.central.latest else { return "none" }
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
            if let e = r.error { Text(e) }
        }
        .onChange(of: r.isRecording) { _, recording in
            if !recording { model.recordingFinished() }
        }
    }
}
#endif
