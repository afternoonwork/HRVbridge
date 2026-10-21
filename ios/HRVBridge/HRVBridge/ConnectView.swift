import HRVCore
import SwiftUI

/// Shown first while no API key is saved. After "Save key" one read call checks the key.
struct ConnectView: View {
    let model: AppModel
    @State private var key = ""
    @State private var storageFailed = false
    @FocusState private var fieldFocused: Bool

    private var measurement: MeasurementController { model.measurement }

    var body: some View {
        ConnectScreen(key: $key,
                      state: screenState,
                      fieldFocused: $fieldFocused,
                      save: save,
                      proceed: { model.showConnect = false })
            .sensoryFeedback(trigger: measurement.keyCheck) { _, new in
                switch new {
                // Only the brief's moments: a rejected key, not an accepted one.
                case .rejected?: return .error
                default: return nil
                }
            }
            .onChange(of: screenState) { _, state in
                if case let .problem(text) = state { AccessibilityNotification.Announcement(text).post() }
            }
    }

    private var screenState: ConnectScreen.Phase {
        if storageFailed { return .problem("Couldn't store the key securely on this device.") }
        if measurement.checkingKey { return .checking }
        switch measurement.keyCheck {
        case .valid?: return measurement.apiKey == nil ? .idle : .connected(checked: true)
        case .unknown?: return measurement.apiKey == nil ? .idle : .connected(checked: false)
        case .rejected?: return .problem(ConnectScreen.rejectedText)
        case nil: return measurement.apiKey == nil ? .idle : .connected(checked: true)
        }
    }

    private func save() {
        fieldFocused = false
        switch measurement.saveApiKey(key) {
        case .saved:
            key = ""
            storageFailed = false
        case .blank:
            break
        case .storageFailed:
            storageFailed = true
        }
    }
}

/// The connect screen for a given state (also used for the CI screenshots).
struct ConnectScreen: View {
    enum Phase: Equatable {
        case idle
        case checking
        /// Key rejected or could not be stored: shown under the field.
        case problem(String)
        /// `checked` false: Intervals.icu could not be reached; the key is saved anyway.
        case connected(checked: Bool)
    }

    static let rejectedText = "Intervals.icu did not accept this key. Check that you copied all of it, then save again."

    @Binding var key: String
    let state: Phase
    var fieldFocused: FocusState<Bool>.Binding
    var save: () -> Void = {}
    var proceed: () -> Void = {}

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Connect to Intervals.icu")
                    .font(.screenTitle)
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 32)
                if case let .connected(checked) = state {
                    connectedCard(checked: checked)
                } else {
                    Text("Paste your personal API key. You find it in Intervals.icu under Settings, Developer Settings.")
                        .font(.body).foregroundStyle(Theme.ink)
                    field
                }
                Notice(lead: "Keep your morning values intact",
                       text: "This app writes your resting HR and HRV (rMSSD, SDNN) to today's wellness record in Intervals.icu. If another service (e.g. Garmin, Oura, Whoop) also sends resting HR or HRV to Intervals.icu, it may overwrite these values. To prevent that, open Intervals.icu settings, and for each connected service untick Resting HR and HRV.")
                    .padding(.top, 8)
            }
            .padding(.horizontal, Space.margin)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            keyArea
                .frame(maxWidth: .infinity)
                .padding(.top, 12)
                .padding(.bottom, Space.bottom)
                .background(Theme.bg)
        }
        .background(Theme.bg.ignoresSafeArea())
    }

    private var rejected: Bool {
        if case .problem = state { return true }
        return false
    }

    private var field: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("API key").font(.caption).foregroundStyle(Theme.ink2)
            SecureField("", text: $key)
                .font(.body)
                .foregroundStyle(Theme.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)
                .submitLabel(.done)
                .onSubmit { if !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { save() } }
                .focused(fieldFocused)
                .accessibilityLabel("API key")
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surface))
                .overlay(RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(rejected ? Theme.ink : Theme.lineStrong, lineWidth: rejected ? 2 : 1))
            if case let .problem(text) = state {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle").accessibilityHidden(true)
                    Text(text)
                }
                .font(.footnote)
                .foregroundStyle(Theme.ink)
                .accessibilityElement(children: .combine)
            } else {
                Text("Stored on this phone only and sent only to Intervals.icu.")
                    .font(.footnote).foregroundStyle(Theme.ink2)
            }
        }
    }

    private func connectedCard(checked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle").foregroundStyle(Theme.ink).accessibilityHidden(true)
                Text("Connected").font(.headline).foregroundStyle(Theme.ink)
            }
            Text(checked ? "Intervals.icu accepted your API key."
                         : "Couldn't reach Intervals.icu to check the key. It is saved and will be used for the next upload.")
                .font(.subheadline).foregroundStyle(Theme.ink2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.line, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var keyArea: some View {
        switch state {
        case .connected:
            Button("Continue", action: proceed).buttonStyle(KeyStyle())
        case .checking:
            Button {} label: {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Checking key…")
                }
            }
            .buttonStyle(KeyStyle())
            .disabled(true)
        default:
            Button("Save key", action: save)
                .buttonStyle(KeyStyle())
                .disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
}
