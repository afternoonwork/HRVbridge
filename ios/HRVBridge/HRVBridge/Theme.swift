import SwiftUI
import UIKit

/// Design tokens from the HRV Bridge design system (docs/ui-implementation-brief.md).
/// UI code uses only these names. Light and dark follow the system.
enum Theme {
    static let bg = dynamic(0xFDFDFC, 0x131312)
    static let surface = dynamic(0xFFFFFF, 0x1C1C1A)
    static let key = dynamic(0xF1F1EF, 0x2B2A28)
    static let ink = dynamic(0x1A1A1A, 0xECEAE4)
    static let ink2 = dynamic(0x5F5F5C, 0xA2A09A)
    static let line = dynamic(0xE2E2DF, 0x36352F)
    static let lineStrong = dynamic(0x8A8A86, 0x74726C)
    /// Recording and uploaded only. Never text.
    static let accent = dynamic(0xE2561C, 0xFF6A2B)

    /// The app name is a placeholder: keep it in one place.
    static let appName = "HRV Bridge"

    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255,
                           green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

/// Type styles. Everything scales with Dynamic Type except the dial numerals.
extension Font {
    static let readoutTimer = Font.largeTitle.weight(.medium).monospacedDigit()
    static let readoutValue = Font.title.weight(.medium).monospacedDigit()
    static let screenTitle = Font.title.bold()
    static let dialNumeral = Font.system(size: 11).monospacedDigit()
}

enum Space {
    static let margin: CGFloat = 24
    static let dialToReadout: CGFloat = 36
    static let dialToMetrics: CGFloat = 32
    static let stepsToDial: CGFloat = 12
    static let bottom: CGFloat = 28
}

// MARK: Keys

/// Pill keys. Pressed: scale 0.98 and no outer shadow (90 ms).
struct KeyStyle: ButtonStyle {
    enum Kind { case primary, secondary }
    var kind: Kind = .primary

    func makeBody(configuration: Configuration) -> some View {
        KeyBody(configuration: configuration, kind: kind)
    }

    private struct KeyBody: View {
        let configuration: ButtonStyleConfiguration
        let kind: Kind
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.colorScheme) private var scheme

        var body: some View {
            let pressed = configuration.isPressed
            configuration.label
                .font(.headline)
                .foregroundStyle(foreground)
                .padding(.horizontal, 24)
                .frame(minWidth: kind == .primary ? 200 : 180,
                       minHeight: kind == .primary ? 56 : 52)
                .background(Capsule().fill(fill))
                .overlay(Capsule().strokeBorder(kind == .secondary && isEnabled ? Theme.line : .clear, lineWidth: 1))
                .overlay(highlight)
                .shadow(color: .black.opacity(isEnabled && !pressed ? (scheme == .dark ? 0.55 : 0.12) : 0),
                        radius: 4, y: 3)
                .scaleEffect(pressed ? 0.98 : 1)
                .animation(.easeOut(duration: 0.09), value: pressed)
                .contentShape(Capsule())
        }

        private var fill: Color {
            guard isEnabled else { return Theme.key }
            return kind == .primary ? Theme.ink : Theme.surface
        }

        private var foreground: Color {
            guard isEnabled else { return Theme.ink2 }
            return kind == .primary ? Theme.bg : Theme.ink
        }

        /// Subtle top highlight.
        private var highlight: some View {
            Capsule()
                .strokeBorder(LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.06 : 0.9), .clear],
                                             startPoint: .top, endPoint: .center), lineWidth: 1)
                .opacity(isEnabled ? 1 : 0)
                .allowsHitTesting(false)
        }
    }
}

/// Text-only key, e.g. "Upload anyway".
struct TextKeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Theme.ink)
            .underline()
            .frame(minHeight: 44)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .contentShape(Rectangle())
    }
}

// MARK: Notices and status

/// A notice card. Warnings carry an icon and an outline, never colour.
struct Notice: View {
    var lead: String?
    var text: String
    var warning = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if warning {
                Image(systemName: "exclamationmark.triangle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                if let lead { Text(lead).font(.headline).foregroundStyle(Theme.ink) }
                Text(text).font(.subheadline).foregroundStyle(Theme.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 14)
            .strokeBorder(warning ? Theme.lineStrong : Theme.line, lineWidth: warning ? 1.5 : 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(warning ? "Warning. " + [lead, text].compactMap { $0 }.joined(separator: " ") : [lead, text].compactMap { $0 }.joined(separator: " "))
    }
}

/// Leading mark of a status line.
enum StatusMark: Equatable {
    case hollow, ink, accent, spinner
}

struct StatusDot: View {
    let mark: StatusMark

    var body: some View {
        switch mark {
        case .hollow: Circle().strokeBorder(Theme.ink2, lineWidth: 1.5).frame(width: 8, height: 8)
        case .ink: Circle().fill(Theme.ink).frame(width: 8, height: 8)
        case .accent: Circle().fill(Theme.accent).frame(width: 8, height: 8)
        case .spinner: ProgressView().controlSize(.small)
        }
    }
}

/// One line: mark, optional bold part, text.
struct StatusLine: View {
    let mark: StatusMark
    var bold: String?
    var text: String
    var font: Font = .footnote

    var body: some View {
        HStack(spacing: 6) {
            StatusDot(mark: mark).accessibilityHidden(true)
            if let bold { Text(bold).font(font.weight(.semibold)).foregroundStyle(Theme.ink) }
            Text(text).font(font).foregroundStyle(bold == nil ? Theme.ink : Theme.ink2)
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Step indicator

enum MeasurementStep: Int, CaseIterable {
    case settle, measure, upload

    var title: String {
        switch self {
        case .settle: return "settle"
        case .measure: return "measure"
        case .upload: return "upload"
        }
    }
}

/// Three steps (settle, measure, upload; the strap row shows the connection). Done: filled ink-2; current: filled ink (or accent, or hollow for a problem);
/// later: hollow. The current label is bold, so steps never differ by colour alone.
struct StepIndicator: View {
    enum CurrentMark { case ink, accent, hollow }
    let current: MeasurementStep
    var mark: CurrentMark = .ink

    var body: some View {
        HStack(spacing: 20) {
            ForEach(MeasurementStep.allCases, id: \.self) { step in
                VStack(spacing: 4) {
                    dot(step)
                    Text(step.title)
                        .font(.caption.weight(step == current ? .semibold : .regular))
                        .foregroundStyle(step == current ? Theme.ink : Theme.ink2)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(current.rawValue + 1) of 3: \(current.title)")
    }

    @ViewBuilder private func dot(_ step: MeasurementStep) -> some View {
        if step.rawValue < current.rawValue {
            Circle().fill(Theme.ink2).frame(width: 8, height: 8)
        } else if step == current {
            switch mark {
            case .ink: Circle().fill(Theme.ink).frame(width: 8, height: 8)
            case .accent: Circle().fill(Theme.accent).frame(width: 8, height: 8)
            case .hollow: Circle().strokeBorder(Theme.ink, lineWidth: 1.5).frame(width: 8, height: 8)
            }
        } else {
            Circle().strokeBorder(Theme.ink2, lineWidth: 1).frame(width: 8, height: 8)
        }
    }
}
