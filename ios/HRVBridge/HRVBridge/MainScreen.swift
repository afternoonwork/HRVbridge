import HRVCore
import SwiftUI

/// A finished measurement with a result, plus its upload state.
struct ResultSummary: Equatable {
    var restingHr: Int
    var rmssd: Double
    var sdnn: Double
    var accepted: Int
    var total: Int
    var reliable: Bool
    var upload: UploadStatus
}

/// Everything the main screen shows, as a plain value (also used for the CI screenshots).
enum MainPhase: Equatable {
    case noStrap
    case searching([DiscoveredStrap])
    case connecting(String)
    case connected(String)
    case couldNotConnect(String)
    case bluetooth(HeartRateCentral.BluetoothProblem)
    case settling(secondsLeft: Int, poorContact: Bool)
    case measuring(secondsLeft: Int, beats: Int, poorContact: Bool)
    case failed(FailReason)
    case result(ResultSummary)
    /// Morning questions on: the result waits for answers before it uploads.
    case questions(ResultSummary)
    case notEnoughBeats
}

struct MainScreenState: Equatable {
    var phase: MainPhase
    var heartRate: Int?
    /// Static hand angle (snapshots, and the value at the last update while live).
    var handAngle: Double = 0
    /// While measuring: the hand follows this clock every frame.
    var handClock: PhaseClock?
    /// While measuring: accepted beats, for the breathing ring.
    var pulse: BeatPulse?
    var settlingSeconds = 60
    var strapConnected = false
    /// The last measurement was cancelled because the app left the screen.
    var cancelled = false
    /// Retry of earlier uploads on open: footnote under the strap row.
    var retryLine: String?

    func withHand(_ angle: Double) -> MainScreenState {
        var s = self
        s.handAngle = angle
        return s
    }

    var isMeasuring: Bool {
        switch phase {
        case .settling, .measuring: return true
        default: return false
        }
    }

    var poorContact: Bool {
        switch phase {
        case let .settling(_, poor), let .measuring(_, _, poor): return poor
        default: return false
        }
    }

    /// Moments with haptics (apart from "connected" and "poor contact", tracked separately).
    enum Milestone: Equatable { case recording, finishedReliable, finishedUnreliable, uploaded, problem }

    var milestone: Milestone? {
        switch phase {
        case .measuring: return .recording
        case .couldNotConnect, .failed: return .problem
        case .notEnoughBeats: return .finishedUnreliable
        case let .questions(r): return r.reliable ? .finishedReliable : .finishedUnreliable
        case let .result(r):
            switch r.upload {
            case .uploaded: return .uploaded
            case .failed, .reconnectNeeded: return .problem
            case .none, .awaitingAnswers, .inProgress, .notConnected:
                return r.reliable ? .finishedReliable : .finishedUnreliable
            }
        default: return nil
        }
    }

    // MARK: Dial

    var dial: DialModel {
        switch phase {
        case .noStrap, .couldNotConnect: return DialModel(window: .label("no strap"), faint: true)
        case .searching: return DialModel(window: .label("searching"), faint: true)
        case .connecting: return DialModel(window: .label("connecting"), faint: true)
        case let .bluetooth(problem):
            switch problem {
            case .off: return DialModel(window: .label("Bluetooth off"), faint: true)
            case .notAllowed: return DialModel(window: .label("no access"), faint: true)
            case .unsupported: return DialModel(window: .label("no strap"), faint: true)
            }
        case .connected:
            return DialModel(window: .heartRate(heartRate), hand: .ink)
        case let .settling(_, poor), let .measuring(_, _, poor):
            return .running(hr: heartRate, handAngle: handAngle, poorContact: poor)
        case let .failed(reason):
            if reason == .noData || reason == .disconnected || heartRate == nil {
                return DialModel(window: .label("no strap"), faint: true)
            }
            return DialModel(window: .heartRate(heartRate))
        case let .result(r), let .questions(r):
            return DialModel(window: .heartRate(r.restingHr), hand: r.reliable ? .accent : .ink, handAngle: 720,
                             band: r.reliable ? .measure : .muted, bandAngle: 360)
        case .notEnoughBeats:
            return DialModel(window: .heartRate(nil), hand: .ink, handAngle: 720, band: .muted, bandAngle: 360)
        }
    }

    /// Phase name, announced immediately when it changes.
    var phaseTitle: String {
        switch phase {
        case .noStrap: return "No strap connected"
        case .searching: return "Looking for heart rate straps"
        case let .connecting(name): return "Connecting to \(name)"
        case let .connected(name): return "\(name) connected"
        case .couldNotConnect, .bluetooth, .failed: return "Problem"
        case .settling: return "Settling"
        case .measuring: return "Recording"
        case let .result(r): return r.reliable ? "Finished" : "Finished, unreliable"
        case .questions: return "Morning questions"
        case .notEnoughBeats: return "Finished, not enough beats"
        }
    }

    /// The dial as one accessibility element.
    var dialAccessibility: String {
        var parts: [String] = []
        switch phase {
        case let .result(r): parts.append("Resting heart rate \(r.restingHr) beats per minute.")
        case .notEnoughBeats: parts.append("No result.")
        default:
            if case let .label(text) = dial.window {
                parts.append("Heart rate dial, \(text).")
            } else if let hr = heartRate {
                parts.append("Heart rate \(hr) beats per minute.")
            }
        }
        switch phase {
        case let .settling(s, _): parts.append("Settling, \(s) seconds left.")
        case let .measuring(s, _, _): parts.append("Recording, \(s) seconds left.")
        default: break
        }
        return parts.joined(separator: " ")
    }

    /// The warning on screen, if any (announced when it appears).
    var warning: String? {
        switch phase {
        case let .couldNotConnect(name):
            return "Couldn't connect to \(name). Check the strap is on and the electrodes are moist. Close other apps that connect to your heart rate monitor."
        case let .bluetooth(problem):
            switch problem {
            case .off: return "Bluetooth is off. Turn it on in Control Centre or Settings, then try again."
            case .notAllowed: return "Bluetooth access is not allowed. \(Theme.appName) needs it to find your strap. Allow it in Settings."
            case .unsupported: return "This device does not support Bluetooth LE."
            }
        case let .failed(reason): return Self.failText(reason)
        case .notEnoughBeats: return "Not enough beats to calculate results. Please repeat."
        case let .result(r):
            switch r.upload {
            case .none:
                return r.reliable ? nil
                    : "Measurement unreliable, please repeat. \(r.total - r.accepted) of \(r.total) beats were discarded."
            case .awaitingAnswers, .inProgress, .uploaded: return nil
            case .notConnected: return "Not uploaded: connect to Intervals.icu first. It will upload once you have saved your key."
            case .reconnectNeeded: return "Intervals.icu did not accept the stored key. Please reconnect."
            case .failed: return "Upload failed. Your result is saved on this phone. The app will try again next time it opens."
            }
        default:
            if poorContact { return "Poor contact. Moisten the electrodes and make sure the strap fits snugly." }
            if cancelled { return "The measurement was cancelled because the app left the screen. Keep it open while measuring." }
            return nil
        }
    }

    static func failText(_ reason: FailReason) -> String {
        switch reason {
        case .noData: return "No data was received from the strap."
        case .noRR: return "This sensor does not send beat-to-beat data."
        case .implausible: return "The strap's beat-to-beat data does not match its heart rate. Check the strap and try again."
        case .disconnected: return "The strap disconnected."
        }
    }
}

struct MainActions {
    var findStrap: () -> Void = {}
    var connect: (UUID) -> Void = { _ in }
    var start: () -> Void = {}
    var stop: () -> Void = {}
    var done: () -> Void = {}
    /// After a failed measurement, or "Repeat": a new measurement with the same strap.
    var again: () -> Void = {}
    /// "Upload to Intervals.icu", or "Upload anyway" for an unreliable result.
    var upload: () -> Void = {}
    /// Morning questions: the answers, or nil for Skip.
    var submitAnswers: (ReadinessAnswers?) -> Void = { _ in }
    var openSettings: () -> Void = {}
}

/// The main screen for a given state. The dial stays in place; only the rows above and the
/// text and key below change.
struct MainScreenView: View {
    let state: MainScreenState
    var dialAccessibility: String?
    var actions = MainActions()

    /// Measured heights of everything but the dial, so the dial can shrink to keep
    /// warnings and keys visible on small screens (iPhone SE).
    @State private var topHeight: CGFloat = 0
    @State private var belowHeight: CGFloat = 0
    @State private var keysHeight: CGFloat = 0
    /// Heights of the step indicator, the tallest settling or measuring readout (also with poor
    /// contact), and the Start key, measured hidden, so the dial keeps one size from Connected
    /// through measuring. Terminal states only shrink it if they would not fit.
    @State private var stepHeight: CGFloat = 0
    @State private var belowProbe: CGFloat = 0
    @State private var keysProbe: CGFloat = 0
    /// Off until the first layouts are measured, so opening the screen does not animate.
    @State private var animateDial = false

    var body: some View {
        if case .questions = state.phase {
            QuestionsPage(submit: actions.submitAnswers)
        } else {
            dialLayout
        }
    }

    private var dialLayout: some View {
        GeometryReader { geo in
            let running = topHeight + Space.dialToReadout - 12 + belowProbe + keysProbe + 12 + Space.bottom
            let current = topHeight + belowSpacing + belowHeight + keysHeight
            // Active states (poor contact included) keep the reserved size; only terminal states may shrink it.
            let active: Bool = {
                if case .connected = state.phase { return true }
                return state.isMeasuring
            }()
            let avail = geo.size.height - 8 - Space.stepsToDial - 8
            let fit = max(160, min(HeartDial.size(forWidth: geo.size.width), avail - running))
            // Spare height, from the reserved heights so the dial does not move between active
            // states: half above the block, half between the block and the keys.
            let lift = max(0, avail - running - fit) / 2
            let dialSize = active ? fit : max(160, min(fit, avail - current))
            // Terminal states keep the dial where it was while their content fits below it.
            let topLift = active ? lift : max(0, min(lift, avail - current - dialSize))
            ScrollView {
                VStack(spacing: 0) {
                    top
                        .frame(minHeight: max(28, stepHeight))
                        .onGeometryChange(for: CGFloat.self, of: \.size.height) { topHeight = $0 }
                        .padding(.top, 8 + topLift)
                        .animation(animateDial ? .easeInOut(duration: 0.25) : nil, value: topLift)
                    dial
                        .frame(width: dialSize, height: dialSize)
                        .animation(animateDial ? .easeInOut(duration: 0.25) : nil, value: dialSize)
                        .padding(.top, Space.stepsToDial)
                    below
                        .onGeometryChange(for: CGFloat.self, of: \.size.height) { belowHeight = $0 }
                        .padding(.top, belowSpacing)
                }
                .padding(.horizontal, Space.margin)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background(alignment: .top) { probes }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                keys
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)
                    .padding(.bottom, Space.bottom)
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { keysHeight = $0 }
                    .background(Theme.bg)
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .task {
            try? await Task.sleep(for: .milliseconds(500))
            animateDial = true
        }
    }

    /// Not shown: measures the views the dial must leave room for, at the content width.
    private var probes: some View {
        VStack(spacing: 0) {
            StepIndicator(current: .settle)
                .onGeometryChange(for: CGFloat.self, of: \.size.height) { stepHeight = $0 }
            ZStack {
                MainScreenView(state: MainScreenState(phase: .settling(secondsLeft: 60, poorContact: false))).below
                MainScreenView(state: MainScreenState(phase: .measuring(secondsLeft: 60, beats: 60, poorContact: false))).below
                MainScreenView(state: MainScreenState(phase: .settling(secondsLeft: 60, poorContact: true))).below
                MainScreenView(state: MainScreenState(phase: .measuring(secondsLeft: 60, beats: 60, poorContact: true))).below
            }
            .padding(.horizontal, Space.margin)
            .onGeometryChange(for: CGFloat.self, of: \.size.height) { belowProbe = $0 }
            MainScreenView(state: MainScreenState(phase: .connected(""))).keys
                .onGeometryChange(for: CGFloat.self, of: \.size.height) { keysProbe = $0 }
        }
        .fixedSize(horizontal: false, vertical: true)
        .hidden()
        .accessibilityHidden(true)
    }

    /// While measuring the hand sweeps with the wall clock every frame, also with Reduce
    /// Motion (it carries the time, like a seconds hand).
    @ViewBuilder private var dial: some View {
        let label = dialAccessibility ?? state.dialAccessibility
        if let clock = state.handClock {
            TimelineView(.animation) { _ in
                HeartDial(model: state.withHand(clock.angle(at: Clock.nowMs())).dial,
                          accessibilityText: label, pulse: state.pulse)
            }
        } else {
            HeartDial(model: state.dial, accessibilityText: label)
        }
    }

    private var belowSpacing: CGFloat {
        switch state.phase {
        case .result, .notEnoughBeats: return Space.dialToMetrics
        default: return Space.dialToReadout - 12
        }
    }

    // MARK: Above the dial

    @ViewBuilder private var top: some View {
        switch state.phase {
        case .noStrap, .couldNotConnect, .bluetooth:
            strapRow(StatusLine(mark: .hollow, text: "No strap connected"))
        case .searching:
            strapRow(StatusLine(mark: .spinner, text: "Looking for heart rate straps…"))
        case let .connecting(name):
            strapRow(StatusLine(mark: .spinner, text: "Connecting to \(name)…"))
        case let .connected(name):
            VStack(spacing: 12) {
                if state.cancelled, let w = state.warning { Notice(text: w, warning: true) }
                strapRow(StatusLine(mark: .ink, bold: name, text: "connected"))
            }
        case let .settling(_, poor):
            StepIndicator(current: .settle, mark: poor ? .hollow : .ink)
        case let .measuring(_, _, poor):
            StepIndicator(current: .measure, mark: poor ? .hollow : .accent)
        case .failed:
            StepIndicator(current: .settle, mark: .hollow)
        case let .result(r):
            StepIndicator(current: .upload, mark: r.upload == .uploaded ? .accent : .hollow)
        case .questions:
            StepIndicator(current: .upload, mark: .hollow)
        case .notEnoughBeats:
            StepIndicator(current: .upload, mark: .hollow)
        }
    }

    private func strapRow(_ line: StatusLine) -> some View {
        VStack(spacing: 4) {
            line
            if let retry = state.retryLine {
                Text(retry).font(.footnote).foregroundStyle(Theme.ink2).multilineTextAlignment(.center)
            }
        }
    }

    // MARK: Below the dial

    @ViewBuilder private var below: some View {
        switch state.phase {
        case .noStrap:
            Notice(text: "Put the strap on and moisten the electrodes. Close other apps that connect to your heart rate monitor.")
        case let .searching(found):
            strapList(found)
        case .connecting:
            EmptyView()
        case .connected:
            VStack(spacing: 4) {
                Text("Sit still, then start.").font(.subheadline).foregroundStyle(Theme.ink)
                Text(state.settlingSeconds == 0 ? "No settling time, then a 60 s measurement."
                                                : "\(state.settlingSeconds) s settling, then a 60 s measurement.")
                    .font(.footnote).foregroundStyle(Theme.ink2)
            }
            .multilineTextAlignment(.center)
        case .couldNotConnect, .bluetooth, .failed, .notEnoughBeats:
            if let w = state.warning { Notice(text: w, warning: true) }
        case let .settling(s, poor):
            readout(mark: nil, label: "Settling", seconds: s, detail: "Sit still and breathe normally.", poor: poor)
        case let .measuring(s, beats, poor):
            readout(mark: .accent, label: "Recording", seconds: s,
                    detail: beats == 1 ? "1 beat recorded" : "\(beats) beats recorded", poor: poor)
        case let .result(r):
            result(r)
        case .questions:
            EmptyView()
        }
    }

    private func strapList(_ found: [DiscoveredStrap]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(found.enumerated()), id: \.element.id) { i, strap in
                if i > 0 { Divider().overlay(Theme.line).padding(.leading, 16) }
                Button { actions.connect(strap.id) } label: {
                    HStack {
                        Text(strap.name).font(.body).foregroundStyle(Theme.ink)
                        Spacer()
                        if strap.rssi != 0 {
                            Text("\(strap.rssi) dBm").font(.footnote).foregroundStyle(Theme.ink2)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Connects to this strap")
            }
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }

    @ViewBuilder private func readout(mark: StatusMark?, label: String, seconds: Int, detail: String,
                                      poor: Bool) -> some View {
        if poor {
            VStack(spacing: 12) {
                HStack(spacing: 6) {
                    if let mark { StatusDot(mark: mark) }
                    Text(label).font(.subheadline.weight(.semibold))
                    Text("\(seconds) s").font(.subheadline.monospacedDigit())
                }
                .foregroundStyle(Theme.ink)
                .accessibilityElement(children: .combine)
                if let w = state.warning { Notice(text: w, warning: true) }
            }
        } else {
            VStack(spacing: 4) {
                HStack(spacing: 6) {
                    if let mark { StatusDot(mark: mark).accessibilityHidden(true) }
                    Text(label).font(.subheadline).foregroundStyle(Theme.ink)
                }
                Text("\(seconds) s").font(.readoutTimer).foregroundStyle(Theme.ink)
                    .accessibilityLabel("\(seconds) seconds left")
                Text(detail).font(.footnote).foregroundStyle(Theme.ink2)
            }
            .multilineTextAlignment(.center)
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder private func result(_ r: ResultSummary) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 40) {
                metric("rMSSD", r.rmssd, dim: !r.reliable)
                metric("SDNN", r.sdnn, dim: !r.reliable)
            }
            // The unreliable warning already says how many beats were discarded.
            if r.reliable || r.upload != .none {
                Text("\(r.accepted) of \(r.total) beats used").font(.footnote).foregroundStyle(Theme.ink2)
            }
            switch r.upload {
            case .inProgress:
                StatusLine(mark: .spinner, text: "Uploading to Intervals.icu…")
            case .uploaded:
                HStack(spacing: 6) {
                    StatusLine(mark: .accent, text: "Uploaded to Intervals.icu")
                    Image(systemName: "checkmark").font(.footnote.weight(.semibold)).foregroundStyle(Theme.ink)
                        .accessibilityHidden(true)
                }
            default:
                if let w = state.warning { Notice(text: w, warning: true) }
            }
        }
    }

    private func metric(_ name: String, _ value: Double, dim: Bool) -> some View {
        VStack(spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(String(format: "%.1f", value)).font(.readoutValue)
                Text("ms").font(.footnote)
            }
            .foregroundStyle(dim ? Theme.ink2 : Theme.ink)
            Text(name).font(.caption).foregroundStyle(Theme.ink2)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        switch state.phase {
        case .noStrap:
            Button("Find strap", action: actions.findStrap).buttonStyle(KeyStyle())
        case .searching, .connecting, .bluetooth(.unsupported):
            EmptyView()
        case .connected:
            Button(action: actions.start) {
                HStack(spacing: 8) {
                    Circle().fill(Theme.accent).frame(width: 8, height: 8).accessibilityHidden(true)
                    Text("Start")
                }
            }
            .buttonStyle(KeyStyle())
        case .couldNotConnect, .bluetooth(.off):
            Button("Try again", action: actions.findStrap).buttonStyle(KeyStyle())
        case .bluetooth(.notAllowed):
            Button("Open Settings", action: actions.openSettings).buttonStyle(KeyStyle())
        case .settling, .measuring:
            Button("Stop", action: actions.stop).buttonStyle(KeyStyle(kind: .secondary))
        case let .failed(reason):
            if reason == .noRR {
                Button("Done", action: actions.done).buttonStyle(KeyStyle())
            } else {
                Button("Try again", action: actions.again).buttonStyle(KeyStyle())
            }
        case let .result(r):
            if !r.reliable && r.upload == .none {
                VStack(spacing: 4) {
                    Button("Repeat", action: actions.again).buttonStyle(KeyStyle())
                    Button("Upload anyway", action: actions.upload).buttonStyle(TextKeyStyle())
                }
            } else if r.upload == .none {
                // Nothing uploads until the user has seen the result and taps Upload.
                VStack(spacing: 4) {
                    Button("Upload to Intervals.icu", action: actions.upload).buttonStyle(KeyStyle())
                    Button("close without uploading", action: actions.done).buttonStyle(TextKeyStyle())
                }
            } else {
                Button("Done", action: actions.done).buttonStyle(KeyStyle())
            }
        case .notEnoughBeats:
            Button("Repeat", action: actions.again).buttonStyle(KeyStyle())
        case .questions:
            EmptyView()
        }
    }
}
