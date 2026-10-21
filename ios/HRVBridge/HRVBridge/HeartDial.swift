import SwiftUI

/// What the dial shows. The hand angle is cumulative degrees: settling is the first turn
/// (0 to 360), measuring the second (360 to 720), done is parked at 720. The hand sweeps
/// with the wall clock like a seconds hand (decided 21 Oct 2026); beats show as the
/// orange ring breathing, not as hand steps.
struct DialModel: Equatable {
    enum Window: Equatable {
        case heartRate(Int?)
        case label(String)
    }
    enum Hand: Equatable { case hidden, ink, accent }
    enum Band: Equatable { case none, settle, measure, muted }

    var window: Window = .heartRate(nil)
    var hand: Hand = .hidden
    var handAngle: Double = 0
    var band: Band = .none
    /// Degrees from 12 o'clock, 360 = full ring.
    var bandAngle: Double = 0
    var faint = false

    /// The dial for a measurement in progress, derived from the cumulative hand angle.
    static func running(hr: Int?, handAngle: Double, poorContact: Bool) -> DialModel {
        let measuring = handAngle >= 360
        return DialModel(window: poorContact ? .label("poor contact") : .heartRate(hr),
                         hand: measuring ? .accent : .ink,
                         handAngle: handAngle,
                         band: handAngle <= 0 ? .none : (measuring ? .measure : .settle),
                         bandAngle: measuring ? handAngle - 360 : handAngle)
    }
}

/// The heart rate dial, drawn in code. Size: min(264, width - 112).
struct HeartDial: View {
    let model: DialModel
    var accessibilityText: String = ""
    /// Set while measuring: on each accepted beat the orange ring eases up to full
    /// opacity and fades back to about 35% over that beat's RR interval.
    var pulse: BeatPulse?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    @ScaledMetric(relativeTo: .title) private var hrSize: CGFloat = 32

    var body: some View {
        GeometryReader { geo in
            let d = min(geo.size.width, geo.size.height)
            let r = d / 2
            ZStack {
                face(r)
                band(r)
                DialTicks(r: r).opacity(model.faint ? 0.35 : 1)
                window(r)
                hand(r)
            }
            .frame(width: d, height: d)
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    static func size(forWidth width: CGFloat) -> CGFloat {
        min(264, width - 112)
    }

    private func face(_ r: CGFloat) -> some View {
        Circle()
            .fill(Theme.surface)
            .overlay(Circle().strokeBorder(Theme.line, lineWidth: 1))
            .overlay(Circle().strokeBorder(
                LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.06 : 0.9), .clear],
                               startPoint: .top, endPoint: .center), lineWidth: 1))
            .shadow(color: .black.opacity(scheme == .dark ? 0.55 : 0.12), radius: 9, y: 6)
    }

    @ViewBuilder private func band(_ r: CGFloat) -> some View {
        if model.band != .none {
            let inner = 0.932 * r, outer = 0.970 * r
            let mid = (inner + outer) / 2
            Circle()
                .trim(from: 0, to: min(1, max(0, model.bandAngle / 360)))
                .stroke(model.band == .measure ? Theme.accent : Theme.ink2.opacity(0.45),
                        style: StrokeStyle(lineWidth: outer - inner, lineCap: .butt))
                .rotationEffect(.degrees(-90))
                .frame(width: mid * 2, height: mid * 2)
                .modifier(Breathing(pulse: breathing ? pulse : nil))
        }
    }

    /// Only the orange measuring ring breathes. Opacity only, so also with Reduce Motion.
    private var breathing: Bool { model.band == .measure && pulse != nil }

    /// Orange with white text while a strap is connected; the faint "no strap" states keep
    /// the plain window.
    private func window(_ r: CGFloat) -> some View {
        let filled = !model.faint && model.window != .heartRate(nil)
        return ZStack {
            RoundedRectangle(cornerRadius: 10).fill(filled ? Theme.accentFill : Theme.bg)
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(LinearGradient(colors: [.black.opacity(scheme == .dark ? 0.4 : filled ? 0.2 : 0.08), .clear],
                                             startPoint: .top, endPoint: .bottom), lineWidth: 2)
            if !filled { RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.line, lineWidth: 1) }
            switch model.window {
            case let .heartRate(hr):
                Text(hr.map(String.init) ?? "")
                    .font(.system(size: hrSize, weight: .semibold).monospacedDigit())
                    .foregroundStyle(filled ? .white : Theme.ink)
                    .minimumScaleFactor(0.5)
            case let .label(text):
                // On orange: white on #C84A16 is 4.7:1, so the 14 pt bold label meets WCAG AA.
                Text(text)
                    .font(filled ? .system(size: 14, weight: .bold) : .footnote.weight(.semibold))
                    .foregroundStyle(filled ? .white : Theme.ink2)
                    .minimumScaleFactor(0.6)
            }
        }
        .lineLimit(1)
        .frame(width: 96, height: 46)
        .offset(y: 0.379 * r)
    }

    @ViewBuilder private func hand(_ r: CGFloat) -> some View {
        if model.hand != .hidden {
            let colour = model.hand == .accent ? Theme.accent : Theme.ink
            let tail = 0.136 * r, length = 0.879 * r
            ZStack {
                // Pointing up from the centre, rotated about the dial centre.
                Capsule()
                    .fill(colour)
                    .frame(width: 2, height: length + tail)
                    .offset(y: -(length - tail) / 2)
                    .rotationEffect(.degrees(model.handAngle))
                Circle().fill(colour).frame(width: 14, height: 14)
                Circle().fill(Theme.surface).frame(width: 4, height: 4)
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: model.hand)
        }
    }

}

/// The 60 ticks and numerals. Its own view with only `r` as input, so SwiftUI skips
/// redrawing it while the hand sweeps every frame.
private struct DialTicks: View, Equatable {
    let r: CGFloat

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            for i in 0..<60 {
                let major = i % 5 == 0
                let a = CGFloat(i) / 60 * 2 * CGFloat.pi
                let from = (major ? 0.818 : 0.856) * r, to = 0.917 * r
                var p = Path()
                p.move(to: point(c, a, from))
                p.addLine(to: point(c, a, to))
                ctx.stroke(p, with: .color(Theme.ink), lineWidth: major ? 2.5 : 1)
                if major {
                    let n = i == 0 ? 60 : i
                    ctx.draw(Text("\(n)").font(.dialNumeral).foregroundStyle(Theme.ink2),
                             at: point(c, a, 0.712 * r), anchor: .center)
                }
            }
        }
    }

    private func point(_ c: CGPoint, _ angle: CGFloat, _ radius: CGFloat) -> CGPoint {
        CGPoint(x: c.x + radius * sin(angle), y: c.y - radius * cos(angle))
    }
}

/// The ring "breathing" with the heart: up quickly, then a smooth fade over the rest of
/// the beat (never on/off). Without a pulse the ring is fully opaque.
private struct Breathing: ViewModifier {
    let pulse: BeatPulse?

    func body(content: Content) -> some View {
        if let pulse {
            // RR is on the session clock; the simulated strap may run it faster.
            let beat = max(0.3, pulse.rrMs / 1000 / Clock.currentSpeed)
            content.keyframeAnimator(initialValue: 0.35, trigger: pulse.id) { view, opacity in
                view.opacity(opacity)
            } keyframes: { _ in
                KeyframeTrack {
                    CubicKeyframe(1.0, duration: beat * 0.25)
                    CubicKeyframe(0.35, duration: beat * 0.75)
                }
            }
        } else {
            content
        }
    }
}
