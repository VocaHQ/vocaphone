import SwiftUI

struct SetupStep: Identifiable {
    let id: String
    let title: String
    let detail: String
    let isComplete: Bool
    let actionTitle: String?
    let action: (() -> Void)?
}

/// The dominant failure mode for this app is an unfinished setup spread across
/// iOS Settings, a gateway, and a permission prompt. Showing the remaining work
/// as a checklist beats burying it in prose.
struct SetupChecklistView: View {
    let steps: [SetupStep]
    let recheck: @MainActor () async -> Void
    @State private var isChecking = false
    @State private var lastCheckedAt: Date?

    var body: some View {
        Section {
            ForEach(steps) { step in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Image(systemName: step.isComplete ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(step.isComplete ? .green : .secondary)
                            .font(.title3)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(step.title)
                                .font(.subheadline.weight(.semibold))
                            Text(step.detail)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(step.isComplete ? "Done" : "Not done")

                    if !step.isComplete,
                       let actionTitle = step.actionTitle,
                       let action = step.action
                    {
                        Button(actionTitle, action: action)
                            .buttonStyle(.bordered)
                    }
                }
            }

            Button {
                Task {
                    isChecking = true
                    await recheck()
                    lastCheckedAt = Date()
                    isChecking = false
                }
            } label: {
                HStack(spacing: 8) {
                    if isChecking {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    Text(isChecking ? "Checking setup…" : "Refresh setup status")
                }
            }
            .disabled(isChecking)
            .accessibilityHint("Checks the microphone, transcription gateway, and keyboard status.")

            if let lastCheckedAt {
                Label(
                    "Status refreshed \(lastCheckedAt.formatted(date: .omitted, time: .shortened))",
                    systemImage: "checkmark.circle"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        } header: {
            Text("Finish setup")
        } footer: {
            Text(
                "Refresh checks the microphone and gateway immediately. Keyboard access is confirmed when you open the Local Flow keyboard once."
            )
        }
    }
}
