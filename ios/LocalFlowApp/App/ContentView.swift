import SwiftUI
import UIKit

struct ContentView: View {
    @Environment(RecordingCoordinator.self) private var coordinator
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("gatewayURL") private var gatewayURL = ""
    @AppStorage(GatewayStatusPreferences.engineReadyKey)
    private var gatewayEngineReady = false
    @AppStorage(
        KeyboardPreferences.quickDictationKey,
        store: KeyboardPreferences.defaults
    ) private var quickDictationEnabled = true
    @State private var keyboardStatus: KeyboardStatus?
    @State private var keyboardStepDetail = ContentView.keyboardSetupHint
    @State private var testText = ""
    @State private var showsSettings = false
    @State private var showsKeyboardSetup = false

    private static let keyboardSetupHint =
        "Enable Local Flow under Settings › General › Keyboards and allow Full "
            + "Access. This confirms itself the first time the keyboard runs."

    var body: some View {
        NavigationStack {
            List {
                if !isSetupComplete {
                    SetupChecklistView(steps: setupSteps, recheck: recheckSetup)
                }
                sessionSection
                transcriptSection
                practiceSection
            }
            .navigationTitle("Local Flow")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        SettingsView()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .navigationDestination(isPresented: $showsSettings) {
                SettingsView()
            }
            .task {
                await recheckSetup()
                await coordinator.recoverRecentSession()
                coordinator.prepareQuickDictationIfEnabled()
            }
            .onChange(of: scenePhase) { previousPhase, currentPhase in
                guard previousPhase != .active, currentPhase == .active else { return }
                Task { await recheckSetup() }
            }
        }
        .sheet(isPresented: $showsKeyboardSetup) {
            KeyboardSetupGuide(verify: verifyKeyboardAccess)
        }
        .overlay {
            if showsKeyboardReturnGuide {
                KeyboardReturnGuide(
                    startedAt: coordinator.activeRecord?.createdAt ?? Date(),
                    finish: coordinator.requestFinish,
                    cancel: coordinator.cancel
                )
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showsKeyboardReturnGuide)
    }

    // MARK: - Setup

    private var setupSteps: [SetupStep] {
        [
            SetupStep(
                id: "gateway",
                title: "Connect your transcription gateway",
                detail: gatewayEngineReady
                    ? "Gateway, token, and model are ready."
                    : "Add the gateway URL and pairing token in Settings, then tap Save and test.",
                isComplete: gatewayEngineReady,
                actionTitle: "Set up gateway",
                action: { showsSettings = true }
            ),
            SetupStep(
                id: "microphone",
                title: "Allow microphone access",
                detail: coordinator.microphonePermissionState.setupDetail,
                isComplete: coordinator.microphonePermissionGranted,
                actionTitle: coordinator.microphonePermissionState.actionTitle,
                action: microphoneSetupAction
            ),
            SetupStep(
                id: "keyboard",
                title: "Add the keyboard with Full Access",
                detail: keyboardStepDetail,
                isComplete: keyboardStatus?.hasFullAccess == true,
                actionTitle: "Set up keyboard access",
                action: { showsKeyboardSetup = true }
            ),
        ]
    }

    private var isSetupComplete: Bool {
        setupSteps.allSatisfy(\.isComplete)
    }

    /// Date formatting is comparatively expensive and `body` re-evaluates
    /// often, so the string is built when the status actually changes.
    private func reloadKeyboardStatus() {
        coordinator.refreshMicrophonePermission()
        let status = coordinator.keyboardStatus()
        keyboardStatus = status
        switch status {
        case let .some(status) where status.hasFullAccess:
            let seen = status.lastSeenAt.formatted(date: .abbreviated, time: .shortened)
            keyboardStepDetail = "Keyboard ready. Last opened \(seen)."
        case .some:
            keyboardStepDetail =
                "The keyboard was added, but Full Access is still off. Enable it, then open the Local Flow keyboard once."
        case .none:
            keyboardStepDetail = Self.keyboardSetupHint
        }
    }

    private func recheckSetup() async {
        reloadKeyboardStatus()
        await coordinator.refreshGatewayHealth()
        reloadKeyboardStatus()
    }

    private func verifyKeyboardAccess() {
        Task { await recheckSetup() }
    }

    private func microphoneSetupAction() {
        switch coordinator.microphonePermissionState {
        case .notDetermined:
            coordinator.requestMicrophonePermission()
        case .denied:
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url)
        case .granted:
            break
        }
    }

    // MARK: - Sections

    private var sessionSection: some View {
        Section("Current session") {
            LabeledContent("State", value: coordinator.stateLabel)

            if coordinator.isRecording {
                RecordingMeter()
                Text("Recording continues while you return to the previous app.")
                    .font(.footnote)
            }

            if coordinator.isQuickDictationReady,
               let expiresAt = coordinator.quickDictationExpiresAt
            {
                LabeledContent("Quick Dictation", value: "Ready")
                Text(
                    "Microphone ready until "
                        + expiresAt.formatted(date: .omitted, time: .shortened)
                        + ". Later Dictate taps stay in the current app."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
                Button("Turn off Quick Dictation", role: .destructive) {
                    quickDictationEnabled = false
                }
            }

            if let message = coordinator.message {
                Text(message)
                    .foregroundStyle(coordinator.hasError ? .red : .secondary)
            }

            if coordinator.isRecording {
                Button("Finish recording") { coordinator.requestFinish() }
                    .buttonStyle(.borderedProminent)
                Button("Cancel", role: .destructive) { coordinator.cancel() }
            } else {
                Button("Start microphone test") { coordinator.startInAppTest() }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private var transcriptSection: some View {
        Section("Transcripts") {
            if let transcript = coordinator.transcript, !transcript.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Latest")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(transcript)
                        .textSelection(.enabled)
                }
                Button {
                    UIPasteboard.general.string = transcript
                } label: {
                    Label("Copy latest transcript", systemImage: "doc.on.doc")
                }
            }
            NavigationLink {
                TranscriptHistoryView()
            } label: {
                Label("All transcripts", systemImage: "clock.arrow.circlepath")
            }
        }
    }

    private var practiceSection: some View {
        Section("Try the keyboard") {
            TextField("Switch to the Local Flow keyboard here", text: $testText, axis: .vertical)
                .lineLimit(3...6)

            if coordinator.isDictatingIntoContainingApp {
                // Dictating into this field needs no app switch, so the flow is
                // explained inline instead of behind a full-screen hand-off.
                HStack(spacing: 8) {
                    Circle()
                        .fill(.red)
                        .frame(width: 10, height: 10)
                    Text("Listening")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    RecordingMeter()
                        .frame(width: 90)
                }
                .accessibilityElement(children: .combine)

                Text("Tap Finish on the keyboard and the transcript drops into this field.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Button("Finish recording") { coordinator.requestFinish() }
                    .buttonStyle(.borderedProminent)
                Button("Cancel", role: .destructive) { coordinator.cancel() }
            } else {
                Text(
                    "Dictating here keeps you in Local Flow. From another app, the "
                        + "keyboard opens Local Flow to record — swipe back once recording "
                        + "begins, then tap Finish in the keyboard."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
    }

    private var showsKeyboardReturnGuide: Bool {
        if coordinator.isKeyboardRecording { return true }
#if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-previewKeyboardReturnGuide")
#else
        return false
#endif
    }
}

/// The level updates several times a second. Keeping it in a leaf view means
/// only this redraws, instead of every screen observing the coordinator.
private struct RecordingMeter: View {
    @Environment(RecordingCoordinator.self) private var coordinator

    var body: some View {
        ProgressView(value: Double(coordinator.meterLevel))
            .tint(.red)
    }
}

private struct RecordingPulse: View {
    @Environment(RecordingCoordinator.self) private var coordinator

    private var level: CGFloat {
        coordinator.isKeyboardRecording ? CGFloat(coordinator.meterLevel) : 0.42
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.red.opacity(0.12))
                .frame(width: 108, height: 108)
            Circle()
                .fill(.red)
                .frame(width: 58 + level * 18, height: 58 + level * 18)
                .animation(.linear(duration: 0.12), value: level)
            Image(systemName: "mic.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(.white)
        }
    }
}

private struct KeyboardReturnGuide: View {
    let startedAt: Date
    let finish: () -> Void
    let cancel: () -> Void
    @State private var arrowOffset: CGFloat = -18

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(uiColor: .systemBackground),
                    Color(uiColor: .secondarySystemBackground),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 18) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(.red)
                        .frame(width: 10, height: 10)
                    Text("RECORDING")
                        .font(.caption.bold())
                    Spacer()
                    Text(startedAt, style: .timer)
                        .font(.subheadline.monospacedDigit().weight(.medium))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .background(.thinMaterial, in: Capsule())

                Spacer(minLength: 4)

                RecordingPulse()

                VStack(spacing: 10) {
                    Text("Local Flow is listening")
                        .font(.title.bold())
                        .multilineTextAlignment(.center)
                    Text("Now return to the app where you were typing. Recording will continue safely in the background.")
                        .font(.body)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 28)
                            .fill(.thinMaterial)
                            .frame(height: 74)
                        Capsule()
                            .fill(.primary.opacity(0.75))
                            .frame(width: 112, height: 5)
                            .offset(y: 23)
                        Image(systemName: "arrow.right")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(.blue)
                            .offset(x: arrowOffset, y: -10)
                    }
                    Text("Swipe right across the bottom edge")
                        .font(.headline)
                }

                Text("When the keyboard reappears, tap Finish—or use Finish in the Dynamic Island.")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)

                Spacer(minLength: 4)

                Button("Finish recording here instead", action: finish)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                Button("Cancel recording", role: .destructive, action: cancel)
                    .font(.subheadline)
            }
            .padding(20)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
                arrowOffset = 18
            }
        }
    }
}

struct KeyboardSetupGuide: View {
    @Environment(\.dismiss) private var dismiss
    let verify: () -> Void
    @State private var copiedSettingsPath = false

    private let settingsPath =
        "Settings → General → Keyboard → Keyboards → Local Flow → Allow Full Access"

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(
                        "The Local Flow keyboard can insert your transcript into any text field. It asks the Local Flow app to record because iOS does not allow keyboards to use the microphone.",
                        systemImage: "keyboard.chevron.compact.down"
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }

                Section("Add Local Flow") {
                    setupRow(
                        number: 1,
                        title: "Open the Settings app",
                        detail: "Apple does not provide a public link apps can use to open this specific screen."
                    )
                    setupRow(
                        number: 2,
                        title: "Go to General → Keyboard → Keyboards",
                        detail: "Choose Add New Keyboard…"
                    )
                    setupRow(
                        number: 3,
                        title: "Choose Local Flow",
                        detail: "Then tap Local Flow in the keyboard list and turn on Allow Full Access."
                    )
                }

                Section {
                    Button {
                        UIPasteboard.general.string = settingsPath
                        copiedSettingsPath = true
                    } label: {
                        Label(
                            copiedSettingsPath ? "Settings path copied" : "Copy Settings path",
                            systemImage: copiedSettingsPath ? "checkmark" : "doc.on.doc"
                        )
                    }
                    Text(settingsPath)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                } header: {
                    Text("Make it quicker")
                } footer: {
                    Text("After enabling Full Access, open Local Flow in any text field once so the app can confirm it.")
                }

                Section("Why Full Access?") {
                    Text(
                        "It lets the keyboard use the shared Local Flow session and send recordings to only the gateway you configure. The keyboard itself never records audio."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                Section {
                    Button("I’ve enabled Full Access") {
                        verify()
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .navigationTitle("Set up keyboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func setupRow(number: Int, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(.tint, in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
