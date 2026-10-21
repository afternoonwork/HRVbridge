import HRVCore
import SwiftUI

/// Optional morning questions after a result (Settings, "Morning questions"). Every question
/// is optional; unanswered ones are not sent. Labels are Intervals.icu's own, 1 = best.
struct QuestionsPage: View {
    var initial = ReadinessAnswers()
    var submit: (ReadinessAnswers?) -> Void = { _ in }

    @State private var answers: ReadinessAnswers?

    static let scales: [(title: String, labels: [String], keyPath: WritableKeyPath<ReadinessAnswers, Int?>)] = [
        ("Soreness", ["Low", "Avg", "High", "Extreme"], \.soreness),
        ("Fatigue", ["Low", "Avg", "High", "Extreme"], \.fatigue),
        ("Motivation", ["Extreme", "High", "Avg", "Low"], \.motivation),
        ("Injury", ["None", "Niggle", "Poor", "Injured"], \.injury),
    ]

    var body: some View {
        let current = answers ?? initial
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                StepIndicator(current: .upload, mark: .hollow)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
                Text("Morning questions")
                    .font(.screenTitle)
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                ForEach(Self.scales, id: \.title) { scale in
                    PillRow(title: scale.title, labels: scale.labels,
                            selected: current[keyPath: scale.keyPath].map { $0 - 1 }) { index in
                        var a = current
                        a[keyPath: scale.keyPath] = index.map { $0 + 1 }
                        answers = a
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    PillRow(title: "Sick today?", labels: ["No", "Yes"],
                            selected: current.sick.map { $0 ? 1 : 0 }) { index in
                        var a = current
                        a.sick = index.map { $0 == 1 }
                        answers = a
                    }
                    Text("Yes adds a sick day to your Intervals.icu calendar.")
                        .font(.footnote).foregroundStyle(Theme.ink2)
                }
                Text("Sent to your Intervals.icu wellness record")
                    .font(.footnote)
                    .foregroundStyle(Theme.ink2)
            }
            .padding(.horizontal, Space.margin)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 4) {
                Button("Upload") { submit(current) }.buttonStyle(KeyStyle())
                Button("Skip") { submit(nil) }.buttonStyle(TextKeyStyle())
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 12)
            .padding(.bottom, Space.bottom)
            .background(Theme.bg)
        }
        .background(Theme.bg.ignoresSafeArea())
    }
}

/// A question with pill options. Tapping the selected pill clears it (every question is optional).
struct PillRow: View {
    let title: String
    let labels: [String]
    let selected: Int?
    let select: (Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(Theme.ink)
            HStack(spacing: 8) {
                ForEach(labels.indices, id: \.self) { i in
                    let on = selected == i
                    Button { select(on ? nil : i) } label: {
                        Text(labels[i])
                            .font(.subheadline.weight(on ? .semibold : .regular))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .foregroundStyle(on ? Theme.bg : Theme.ink)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().fill(on ? Theme.ink : Theme.surface))
                            .overlay(Capsule().strokeBorder(on ? Color.clear : Theme.line, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(title) \(labels[i])")
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
    }
}
