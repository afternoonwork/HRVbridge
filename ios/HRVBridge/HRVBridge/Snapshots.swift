#if DEBUG
import HRVCore
import SwiftUI

/// Debug builds only: `-uiSnapshot <name>` shows one fixed screen state, so CI can take
/// simulator screenshots of every state in light and dark (see ios.yml). Names must
/// match `scripts/ios-snapshots.sh`.
enum Snapshots {
    static var requested: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-uiSnapshot"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    private static let strap = "Polar H9 A1B2C3D4"

    private static func result(reliable: Bool = true, upload: UploadStatus) -> MainPhase {
        .result(ResultSummary(restingHr: 52, rmssd: reliable ? 68.4 : 41.7, sdnn: reliable ? 61.2 : 58.3,
                              accepted: reliable ? 64 : 57, total: reliable ? 64 : 63, reliable: reliable, upload: upload))
    }

    static let main: [String: MainScreenState] = [
        "no-strap": MainScreenState(phase: .noStrap),
        "searching": MainScreenState(phase: .searching([
            DiscoveredStrap(id: UUID(), name: strap, rssi: -58),
            DiscoveredStrap(id: UUID(), name: "HRM-Dual 12345", rssi: -77),
        ])),
        "connecting": MainScreenState(phase: .connecting(strap)),
        "connected": MainScreenState(phase: .connected(strap), heartRate: 53, strapConnected: true),
        "retry": MainScreenState(phase: .connected(strap), heartRate: 53, strapConnected: true,
                                 retryLine: "Uploaded 2 earlier measurements."),
        "cancelled": MainScreenState(phase: .connected(strap), heartRate: 54, strapConnected: true, cancelled: true),
        "could-not-connect": MainScreenState(phase: .couldNotConnect(strap)),
        "bluetooth-off": MainScreenState(phase: .bluetooth(.off)),
        "not-allowed": MainScreenState(phase: .bluetooth(.notAllowed)),
        "poor-contact": MainScreenState(phase: .settling(secondsLeft: 41, poorContact: true), heartRate: 55,
                                        handAngle: 115, strapConnected: true),
        "settling": MainScreenState(phase: .settling(secondsLeft: 38, poorContact: false), heartRate: 53,
                                    handAngle: 132, strapConnected: true),
        "measuring": MainScreenState(phase: .measuring(secondsLeft: 36, beats: 22, poorContact: false), heartRate: 52,
                                     handAngle: 504, strapConnected: true),
        "failed-no-rr": MainScreenState(phase: .failed(.noRR), heartRate: 61, strapConnected: true),
        "failed-disconnected": MainScreenState(phase: .failed(.disconnected)),
        "uploading": MainScreenState(phase: result(upload: .inProgress), handAngle: 720, strapConnected: true),
        "uploaded": MainScreenState(phase: result(upload: .uploaded), handAngle: 720, strapConnected: true),
        "unreliable": MainScreenState(phase: result(reliable: false, upload: .none), handAngle: 720, strapConnected: true),
        "not-enough-beats": MainScreenState(phase: .notEnoughBeats, handAngle: 720, strapConnected: true),
        "upload-failed": MainScreenState(phase: result(upload: .failed("HTTP 500")), handAngle: 720, strapConnected: true),
        "not-connected": MainScreenState(phase: result(upload: .notConnected), handAngle: 720, strapConnected: true),
        "questions": MainScreenState(phase: .questions(ResultSummary(restingHr: 52, rmssd: 68.4, sdnn: 61.2, accepted: 64,
                                                                     total: 64, reliable: true, upload: .awaitingAnswers)),
                                     handAngle: 720, strapConnected: true),
    ]

    @ViewBuilder static func view(_ name: String) -> some View {
        if let state = main[name] {
            NavigationStack {
                MainScreenView(state: state)
                    .navigationTitle(Theme.appName)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbarBackground(Theme.bg, for: .navigationBar)
                    .toolbar {
                        if !state.isMeasuring {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button {} label: { Image(systemName: "slider.horizontal.3") }
                            }
                        }
                    }
            }
            .tint(Theme.ink)
        } else {
            switch name {
            case "dial": DialSheet()
            case "questions-answered":
                NavigationStack {
                    QuestionsPage(initial: ReadinessAnswers(soreness: 2, fatigue: 1, motivation: 2, injury: 1, sick: false))
                        .navigationTitle(Theme.appName)
                        .navigationBarTitleDisplayMode(.inline)
                }
                .tint(Theme.ink)
            case "connect": ConnectSnapshot(phase: .idle, key: "")
            case "connect-rejected": ConnectSnapshot(phase: .problem(ConnectScreen.rejectedText), key: "abc123")
            case "connect-connected": ConnectSnapshot(phase: .connected(checked: true), key: "")
            case "settings": NavigationStack { SettingsView(model: AppModel()) }.tint(Theme.ink)
            default: Text("Unknown snapshot \(name)")
            }
        }
    }
}

private struct ConnectSnapshot: View {
    let phase: ConnectScreen.Phase
    @State var key: String
    @FocusState private var focused: Bool

    var body: some View {
        ConnectScreen(key: $key, state: phase, fieldFocused: $focused).tint(Theme.ink)
    }
}

/// The dial on its own in four modes, two by two.
private struct DialSheet: View {
    var body: some View {
        let dials: [(String, DialModel)] = [
            ("no strap", DialModel(window: .label("no strap"), faint: true)),
            ("settling", .running(hr: 53, handAngle: 132, poorContact: false)),
            ("measuring", .running(hr: 52, handAngle: 504, poorContact: false)),
            ("uploaded", DialModel(window: .heartRate(52), hand: .accent, handAngle: 720, band: .measure, bandAngle: 360)),
        ]
        VStack(spacing: 8) {
            HeartDial(model: dials[2].1).frame(width: 264, height: 264).padding(.top, 24)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(dials, id: \.0) { name, model in
                    VStack(spacing: 4) {
                        HeartDial(model: model).frame(width: 150, height: 150)
                        Text(name).font(.caption).foregroundStyle(Theme.ink2)
                    }
                }
            }
            .padding(.horizontal, 16)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
    }
}
#endif
