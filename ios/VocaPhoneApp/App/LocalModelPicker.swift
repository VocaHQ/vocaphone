import SwiftUI

/// The on-device model list, shared by setup and settings.
///
/// The catalog is deliberately large, so this shows only what this iPhone has
/// the memory for. Installed models and downloadable models stay in separate
/// sections so the model currently available for use is easy to find, and every
/// row says the same five things in the same order: what it is, how big it is,
/// what languages it covers, what state it is in, and the one action available.
struct LocalModelPicker: View {
    let manager: LocalModelManager
    /// Setup needs its status line refreshed on every change; Settings does not.
    var onChange: () -> Void = {}
    /// Onboarding presents one guided answer; Settings keeps the full catalog.
    var onboarding = false
    var guidanceLanguage = ""

#if DEBUG
    /// Which model a `#Preview` should draw as "In use". Production leaves this
    /// nil and reads the stored preference, because a canvas must not write the
    /// developer's real model selection to reach one row state.
    var previewSelectedModelID: String?
#endif

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var modelLoadTask: Task<Void, Never>?
    @State private var modelLoadError: String?
    @State private var availableModelsExpanded = false
    @State private var pendingDeletion: LocalModelDescriptor?
    @State private var guidancePriority: ModelGuidancePriority = .balanced
    @State private var guidanceLanguageOverride: String?
    /// A download was started on this page and then stopped.
    ///
    /// The docked Continue goes grey again at that moment, and that is the
    /// only time it needs explaining. Someone who has not started anything yet
    /// has not been told "no" — putting the line there too would be the page
    /// answering a question nobody asked.
    @State private var didCancelDownload = false

    private var usable: [LocalModelDescriptor] { LocalModelCatalog.usableOnDevice }

    private var installedModels: [LocalModelDescriptor] {
        usable.filter { manager.isDownloaded($0.id) || state(for: $0) != .notDownloaded }
    }

    private var guidance: ModelGuidanceResult {
        return LocalModelCatalog.guidance(
            deviceMemoryGB: LocalModelCatalog.deviceMemoryGB,
            intent: ModelGuidanceIntent(
                language: recommendationLanguage,
                priority: guidancePriority
            )
        )
    }

    private var recommendationLanguage: String {
        recommendationLanguages.first ?? LocalModelCatalog.deviceLanguage
    }

    private var recommendationLanguages: [String] {
        Self.recommendationLanguages(
            preferred: guidanceLanguageOverride ?? guidanceLanguage
        )
    }

    /// Enabled keyboards, globe order. See `LocalModelCatalog.spokenLanguages`.
    @MainActor
    static func recommendationLanguages(preferred: String) -> [String] {
        LocalModelCatalog.spokenLanguages(
            device: LocalModelCatalog.deviceLanguage,
            keyboards: KeyboardInputLanguages.snapshot,
            explicit: preferred
        )
    }

    @MainActor
    static func recommendationLanguage(preferred: String) -> String {
        recommendationLanguages(preferred: preferred).first
            ?? LocalModelCatalog.deviceLanguage
    }

    private var deviceLanguageName: String {
        TranscriptionLanguage(rawValue: LocalModelCatalog.deviceLanguage)?.displayName
            ?? Locale.current.localizedString(forLanguageCode: LocalModelCatalog.deviceLanguage)
            ?? LocalModelCatalog.deviceLanguage.uppercased()
    }

    private var guidanceLanguageSelection: String {
        let requested = guidanceLanguageOverride ?? guidanceLanguage
        return requested.isEmpty ? TranscriptionLanguage.automatic.rawValue : requested
    }

    private var picks: [ModelPick] {
        let recommended = LocalModelCatalog.recommendations(
            deviceMemoryGB: LocalModelCatalog.deviceMemoryGB,
            languages: recommendationLanguages
        )
        if onboarding {
            return Array(recommended.prefix(3))
        }
        return recommended
    }

    /// Picks not yet on the phone. The installed ones already have a row above
    /// with their real state; repeating them here would say nothing new.
    private var recommendedPicks: [ModelPick] {
        picks.filter { state(for: $0.model) == .notDownloaded }
    }

    private var availableModels: [LocalModelDescriptor] {
        let recommended = Set(recommendedPicks.map(\.model.id))
        return usable.filter { state(for: $0) == .notDownloaded && !recommended.contains($0.id) }
    }

    private func downloadDetailLine(for id: String) -> String? {
        let parts = [manager.downloadSizeProgress(for: id), manager.downloadTimeRemaining(for: id)]
        let line = parts.compactMap { $0 }.joined(separator: " · ")
        return line.isEmpty ? nil : line
    }

    /// The same guidance run at the other end of the trade-off. Shown as one
    /// concrete swap rather than a grid: the setup card stays a single answer,
    /// but the fact that a small option exists no longer lives only behind a
    /// sheet most people never open.
    private var guidanceAlternative: LocalModelDescriptor? {
        guard onboarding, let current = guidance.model else { return nil }
        let lighter = LocalModelCatalog.guidance(
            deviceMemoryGB: LocalModelCatalog.deviceMemoryGB,
            intent: ModelGuidanceIntent(language: recommendationLanguage, priority: .lighter)
        ).model
        guard let lighter, lighter.id != current.id else { return nil }
        guard state(for: lighter) == .notDownloaded else { return nil }
        return lighter
    }

    private var downloadWarning: DownloadWarning? {
        guard onboarding, manager.downloadingModelIDs.isEmpty else { return nil }
        let pending = picks.map(\.model).filter { !manager.isDownloaded($0.id) }
        guard let model = pending.max(by: { $0.sizeBytes < $1.sizeBytes }) else { return nil }
        let warning = DownloadReadiness.warning(
            sizeBytes: model.sizeBytes,
            freeBytes: manager.availableStorageBytes,
            metered: false
        )
        // Cellular "may charge for data" flickered on and off with path
        // updates. Storage is the only hard stop worth a line here.
        guard case .notEnoughStorage = warning else { return nil }
        return warning
    }

    /// Prefer the model already in use, then FOR YOU, then the first downloaded pick.
    private var onboardingChoice: LocalModelDescriptor? {
        let downloaded = picks.map(\.model).filter {
            manager.isDownloaded($0.id) && !manager.failedIntegrityModelIDs.contains($0.id)
        }
        if let inUse = LocalTranscriptionPreferences.modelIdentifier,
           let match = downloaded.first(where: { $0.id == inUse }) {
            return match
        }
        return downloaded.first ?? picks.first?.model
    }

    /// The one sentence a warning is worth. Written so it says what to do, not
    /// only what is wrong.
    private func warningHeadline(_ warning: DownloadWarning) -> String {
        switch warning {
        case let .notEnoughStorage(freeBytes, requiredBytes):
            "Needs \(DownloadReadiness.byteLabel(requiredBytes)) free · "
                + "\(DownloadReadiness.byteLabel(freeBytes)) available. Free up space first."
        case let .meteredConnection(sizeBytes):
            "This connection may charge for data · \(DownloadReadiness.byteLabel(sizeBytes)) download."
        }
    }

    private func role(of model: LocalModelDescriptor) -> ModelPickRole? {
        picks.first { $0.model.id == model.id }?.role
    }

    /// The five states a model can be in, named once so every surface uses the
    /// same words for them.
    private enum ModelState: Equatable {
        case notDownloaded
        case downloading
        case waiting
        case verifying
        case failedIntegrity
        case loading
        case ready
        case selected

        var label: String {
            switch self {
            case .notDownloaded: "Not downloaded"
            case .downloading: "Downloading"
            case .waiting: "Waiting"
            case .verifying: "Verifying"
            case .failedIntegrity: "Failed verification"
            case .loading: "Loading"
            case .ready: "Ready"
            case .selected: "In use"
            }
        }

        var status: VocaStatus {
            switch self {
            case .notDownloaded: .inactive
            case .downloading, .waiting, .verifying, .loading: .working
            case .failedIntegrity: .failed
            case .ready, .selected: .ready
            }
        }
    }

    private func state(for model: LocalModelDescriptor) -> ModelState {
        if manager.isDownloading(model.id) { return .downloading }
        if manager.isQueued(model.id) { return .waiting }
        if manager.loadingModelID == model.id { return .loading }
        if manager.verifyingModelIDs.contains(model.id) { return .verifying }
        if manager.failedIntegrityModelIDs.contains(model.id) { return .failedIntegrity }
        guard manager.isDownloaded(model.id) else { return .notDownloaded }
#if DEBUG
        if let previewSelectedModelID {
            return previewSelectedModelID == model.id ? .selected : .ready
        }
#endif
        return LocalTranscriptionPreferences.modelIdentifier == model.id ? .selected : .ready
    }

    @ViewBuilder
    var body: some View {
        if onboarding {
            onboardingBody
        } else if usable.isEmpty {
            Section {
                Text("No on-device model fits this iPhone yet.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } else {
            if !installedModels.isEmpty {
                Section("On this iPhone") {
                    ForEach(installedModels) { model in
                        row(for: model)
                    }
                }
            }

            if !recommendedPicks.isEmpty {
                Section {
                    ForEach(recommendedPicks, id: \.model.id) { pick in
                        row(for: pick.model)
                    }
                } header: {
                    Text("Recommended for this iPhone")
                } footer: {
                    Text(
                        "Each of these answers a different question. Every one runs "
                            + "on this iPhone; pick the one that matches how you dictate."
                    )
                }
            }

            Section("Available to download") {
                if availableModels.isEmpty {
                    Text("All compatible models are already on this iPhone.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    DisclosureGroup(isExpanded: $availableModelsExpanded) {
                        ForEach(availableModels) { model in
                            row(for: model)
                        }
                    } label: {
                        Text(
                            "\(availableModels.count) compatible model"
                                + (availableModels.count == 1 ? "" : "s")
                        )
                        .font(.subheadline)
                    }
                }
            }

            if manager.message != nil || modelLoadError != nil {
                Section {
                    if let message = manager.message {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(manager.hasError ? Color.vocaError : .secondary)
                    }
                    if let modelLoadError {
                        Text(modelLoadError)
                            .font(.footnote)
                            .foregroundStyle(Color.vocaError)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var onboardingBody: some View {
        VStack(alignment: .leading, spacing: VocaMetrics.related) {
            if usable.isEmpty {
                Text("No on-device model fits this iPhone yet.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if picks.isEmpty {
                Text(guidance.reason)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                if let warning = downloadWarning {
                    Text(warningHeadline(warning))
                        .font(.footnote)
                        .foregroundStyle(Color.vocaError)
                }
                ForEach(Array(picks.enumerated()), id: \.element.model.id) { index, pick in
                    row(for: pick.model, onboarding: true, forYou: index == 0)
                }
                // Continue unlocks the moment a transfer starts, and a button
                // that merely stops being grey does not explain itself. This
                // says what the next minute is for: the keyboard takes about
                // as long to add as the model takes to arrive.
                if isDownloadingSomething {
                    Text("This keeps downloading while you set up the keyboard.")
                        .font(.footnote)
                        .foregroundStyle(Color.vocaSecondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                } else if explainsDisabledContinue {
                    Text("Choose a model, or skip for now.")
                        .font(.footnote)
                        .foregroundStyle(Color.vocaSecondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let modelLoadError {
                    Text(modelLoadError)
                        .font(.footnote)
                        .foregroundStyle(Color.vocaError)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let message = manager.message, manager.hasError {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(Color.vocaError)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .onAppear { holdRecommendationLanguages() }
        .onChange(of: manager.downloadedModelIDs) { _, _ in
            prepareOnboardingChoiceIfReady()
        }
        // Leaving Choose model ends this page's claim on the selection. The
        // download itself lives on the manager and carries on.
        .onDisappear {
            modelLoadTask?.cancel()
            modelLoadTask = nil
        }
    }

    /// A transfer is running or waiting for a slot.
    private var isDownloadingSomething: Bool {
        !manager.downloadingModelIDs.isEmpty || !manager.queuedModelIDs.isEmpty
    }

    /// Nothing on disk and nothing coming — the state where Continue is grey.
    private var hasUsableDownload: Bool {
        picks.contains {
            manager.isDownloaded($0.model.id)
                && !manager.failedIntegrityModelIDs.contains($0.model.id)
        }
    }

    private var explainsDisabledContinue: Bool {
        didCancelDownload && !hasUsableDownload
    }

    /// Retaken here rather than read per redraw: opening the picker is one of
    /// the two moments the enabled-keyboard list can have changed.
    private func holdRecommendationLanguages() {
        KeyboardInputLanguages.refresh()
    }

    /// Get, the bar, and the check all occupy this capsule so the card does not jump.
    private let onboardingGetWidth: CGFloat = 52
    private let onboardingGetHeight: CGFloat = 28
    private let onboardingCardHeight: CGFloat = 76

    private func onboardingModelCard(
        model: LocalModelDescriptor,
        forYou: Bool,
        state: ModelState
    ) -> some View {
        HStack(alignment: .center, spacing: VocaMetrics.related) {
            VStack(alignment: .leading, spacing: VocaMetrics.tight) {
                HStack(spacing: 6) {
                    Text(model.displayName)
                        .font(.headline)
                        .lineLimit(1)
                    if forYou { forYouBadge }
                    Spacer(minLength: 0)
                }
                Text("\(model.sizeLabel) · \(model.languages)")
                    .font(.subheadline)
                    .foregroundStyle(Color.vocaSecondaryText)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            OnboardingCardTrailing(
                state: state,
                progress: manager.progress(for: model.id),
                getWidth: onboardingGetWidth,
                getHeight: onboardingGetHeight
            )
        }
        .padding(VocaMetrics.padding)
        .frame(minHeight: onboardingCardHeight, maxHeight: onboardingCardHeight)
        .background(
            Color.vocaSurface,
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var forYouBadge: some View {
        Text("FOR YOU")
            .font(.caption2.weight(.bold))
            .tracking(0.2)
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(
                Color(red: 203 / 255, green: 48 / 255, blue: 224 / 255),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .accessibilityLabel("For you")
    }

    /// Get, the ring, and the check occupy this slot so the card does not jump.
    /// After a download the check grows from a small mark into place; an
    /// already-ready model does not replay that motion when the page appears.
    private struct OnboardingCardTrailing: View {
        let state: ModelState
        let progress: Double
        let getWidth: CGFloat
        let getHeight: CGFloat

        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        @State private var didAppear = false

        private enum Kind: Equatable {
            case get
            case ring
            case checkmark
        }

        private var kind: Kind {
            switch state {
            case .notDownloaded, .failedIntegrity: .get
            case .downloading, .waiting, .verifying: .ring
            case .ready, .selected, .loading: .checkmark
            }
        }

        private var ringFraction: Double {
            switch state {
            case .waiting: 0
            case .verifying: 1
            default: progress
            }
        }

        var body: some View {
            ZStack {
                if kind == .get {
                    Text("Get")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: getWidth, height: getHeight)
                        .background(Color.brand, in: Capsule())
                        .transition(.opacity)
                }

                if kind == .ring {
                    AppStoreDownloadRing(fraction: ringFraction)
                        .transition(.opacity)
                }

                if kind == .checkmark {
                    DownloadCompleteCheckmark(
                        size: getHeight,
                        playsPop: didAppear,
                        reduceMotion: reduceMotion
                    )
                    .transition(.identity)
                }
            }
            .frame(width: getWidth, height: VocaMetrics.minimumTarget)
            .animation(didAppear ? .easeOut(duration: 0.18) : nil, value: kind)
            .onAppear { didAppear = true }
        }
    }

    /// Grows from a small mark into place. Opacity stays on so it is not a
    /// finished icon that later enlarges.
    private struct DownloadCompleteCheckmark: View {
        var size: CGFloat
        var playsPop: Bool
        var reduceMotion: Bool

        @State private var scale: CGFloat
        @State private var opacity: Double

        init(size: CGFloat, playsPop: Bool, reduceMotion: Bool) {
            self.size = size
            self.playsPop = playsPop
            self.reduceMotion = reduceMotion
            if playsPop && !reduceMotion {
                _scale = State(initialValue: 0.18)
                _opacity = State(initialValue: 1)
            } else if playsPop {
                _scale = State(initialValue: 1)
                _opacity = State(initialValue: 0)
            } else {
                _scale = State(initialValue: 1)
                _opacity = State(initialValue: 1)
            }
        }

        var body: some View {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: size, weight: .regular))
                .foregroundStyle(Color.brand)
                .scaleEffect(scale)
                .opacity(opacity)
                .accessibilityHidden(true)
                .onAppear {
                    guard playsPop else { return }
                    if reduceMotion {
                        withAnimation(.easeOut(duration: 0.18)) {
                            opacity = 1
                        }
                    } else {
                        withAnimation(.spring(duration: 0.34, bounce: 0.32)) {
                            scale = 1
                        }
                    }
                }
        }
    }

    /// App Store Get: a ring that fills clockwise from 12 o'clock, same slot as Get.
    private struct AppStoreDownloadRing: View {
        var fraction: Double
        var diameter: CGFloat = 28
        var lineWidth: CGFloat = 3.5

        var body: some View {
            let clamped = min(1, max(0, fraction))
            ZStack {
                Circle()
                    .stroke(Color.brand.opacity(0.2), lineWidth: lineWidth)
                Circle()
                    .trim(from: 0, to: clamped == 0 ? 0 : max(0.03, clamped))
                    .stroke(
                        Color.brand,
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: diameter, height: diameter)
            .accessibilityValue("\(Int(clamped * 100)) percent")
        }
    }

    private func row(
        for model: LocalModelDescriptor,
        onboarding: Bool = false,
        forYou: Bool = false
    ) -> some View {
        let state = state(for: model)
        return VStack(alignment: .leading, spacing: VocaMetrics.related + 2) {
            if onboarding {
                Button {
                    handleOnboardingTap(model)
                } label: {
                    onboardingModelCard(
                        model: model,
                        forYou: forYou,
                        state: state
                    )
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityValue(onboardingAccessibilityValue(model: model, state: state))
                .accessibilityHint(onboardingAccessibilityHint(state: state))
            } else {
                VocaStatusLine(
                    status: state.status,
                    title: model.displayName,
                    detail: detail(for: model, state: state)
                )
            }

            switch state {
            case .waiting:
                if onboarding {
                    EmptyView()
                } else {
                    HStack {
                        Text("Waiting")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Button("Cancel") {
                            manager.cancelDownload(model.id)
                        }
                        .buttonStyle(.bordered)
                    }
                }
            case .downloading:
                if onboarding {
                    EmptyView()
                } else {
                    VStack(alignment: .leading, spacing: VocaMetrics.related) {
                        HStack {
                            Text("Downloading")
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Text("\(Int(manager.progress(for: model.id) * 100))%")
                                .font(.subheadline.monospacedDigit())
                        }
                        ProgressView(value: manager.progress(for: model.id))
                        // A bare percentage on a 670 MB download reads as stuck.
                        // The size says how much is actually moving, and the
                        // estimate stays absent until it has settled rather than
                        // swinging wildly through the first seconds.
                        if let detail = downloadDetailLine(for: model.id) {
                            Text(detail)
                                .font(.footnote.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        Button("Cancel") {
                            manager.cancelDownload(model.id)
                        }
                        .buttonStyle(.bordered)
                    }
                }
            case .verifying, .loading:
                if onboarding {
                    EmptyView()
                } else {
                    HStack(spacing: VocaMetrics.related + 2) {
                        ProgressView()
                        Text(
                            state == .verifying
                                ? "Checking every file against its published SHA-256."
                                : manager.loadingMessage ?? "Loading the model…"
                        )
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
                }
            // `.frame(maxWidth:)` outside a button stretches its *layout* and
            // leaves the control its natural size, which is how these ended up
            // the same compact pill as the destructive action below them. The
            // width belongs on the label.
            case .failedIntegrity:
                Button {
                    downloadAndUse(model)
                } label: {
                    Text("Download again").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            case .notDownloaded:
                if onboarding {
                    EmptyView()
                } else {
                    Button {
                        downloadAndUse(model)
                    } label: {
                        Text("Download \(model.sizeLabel)")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .disabled(manager.loadingModelID != nil)
                }
            case .ready:
                if onboarding {
                    EmptyView()
                } else {
                    VocaPrimaryButton(title: "Use this model") {
                        prepare(model, languageOverride: onboarding ? guidanceLanguageOverride : nil)
                    }
                    .disabled(manager.loadingModelID != nil)
                }
            case .selected:
                EmptyView()
            }

            if !onboarding, state == .ready || state == .selected || state == .failedIntegrity {
                // Centred and compact, with room above it. "Use this model" is
                // a full-width filled button, so a full-width destructive one
                // directly beneath it shares an edge with the very action it
                // must never be confused for.
                VocaDestructiveButton(title: "Delete model") {
                    pendingDeletion = model
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, VocaMetrics.related)
            }
        }
        .padding(.vertical, onboarding ? 0 : VocaMetrics.tight)
        .confirmationDialog(
            "Delete \(pendingDeletion?.displayName ?? "this model")?",
            isPresented: Binding(
                get: { pendingDeletion == model },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                manager.deleteReportingResult(model)
                pendingDeletion = nil
                onChange()
            }
            Button("Keep", role: .cancel) { pendingDeletion = nil }
        } message: {
            Text(
                "\(model.sizeLabel) will be freed. You can download it again at any "
                    + "time; dictating offline needs a model on this iPhone."
            )
        }
    }

    /// Size before download, languages before selection, and — for a failed
    /// integrity check — what actually went wrong, because "download failed" and
    /// "the files do not match what was published" call for different responses.
    private func detail(for model: LocalModelDescriptor, state: ModelState) -> String {
        switch state {
        case .failedIntegrity:
            return "The downloaded files do not match their published checksums, so "
                + "the model will not be loaded. Download it again."
        case .selected:
            return "\(model.languages) · in use for dictation on this iPhone"
        case .ready:
            return "\(model.languages) · verified and ready to use offline"
        default:
            var detail = "\(model.sizeLabel) · \(model.languages)"
            // The role, not a bare "recommended": four models cannot all be the
            // recommendation, and which question each one answers is the thing
            // worth saying.
            if let role = role(of: model) {
                detail += " · \(role.label.lowercased())"
            }
            return detail
        }
    }

    private func handleOnboardingTap(_ model: LocalModelDescriptor) {
        switch state(for: model) {
        case .notDownloaded, .failedIntegrity:
            didCancelDownload = false
            downloadAndUse(model)
        case .downloading, .waiting:
            didCancelDownload = true
            manager.cancelDownload(model.id)
        default:
            break
        }
    }

    private func onboardingAccessibilityValue(
        model: LocalModelDescriptor,
        state: ModelState
    ) -> String {
        switch state {
        case .downloading:
            "\(Int(manager.progress(for: model.id) * 100)) percent downloaded"
        case .waiting:
            "Waiting"
        case .ready, .selected:
            "Ready"
        default:
            "Not downloaded"
        }
    }

    private func onboardingAccessibilityHint(state: ModelState) -> String {
        switch state {
        case .downloading, .waiting:
            "Stops this download"
        case .notDownloaded, .failedIntegrity:
            "Starts downloading this model"
        default:
            ""
        }
    }

    private func prepareOnboardingChoiceIfReady() {
        guard onboarding, manager.loadingModelID == nil, let model = onboardingChoice else { return }
        // Stop must not reload a model that is already on disk — that swapped
        // its checkmark for a ring.
        guard state(for: model) == .ready else { return }
        guard LocalTranscriptionPreferences.modelIdentifier != model.id else { return }
        prepare(model, languageOverride: guidanceLanguageOverride, adoptsOnlyIfUnclaimed: true)
    }

    /// Downloads, and makes the model the one in use only if nothing usable is
    /// selected yet. Used from Settings as well as setup: a phone with no model
    /// that finishes downloading one should be able to dictate with it, not
    /// keep saying "No speech-to-text model downloaded" until someone finds
    /// Use this model. With a model already chosen it changes nothing.
    private func downloadAndUse(_ model: LocalModelDescriptor) {
        manager.startDownload(model) {
            guard manager.isDownloaded(model.id) else {
                onChange()
                return
            }
            let inUse = LocalTranscriptionPreferences.modelIdentifier
            if let inUse, manager.isDownloaded(inUse) {
                onChange()
                return
            }
            prepare(
                model,
                languageOverride: onboarding ? guidanceLanguageOverride : nil,
                adoptsOnlyIfUnclaimed: true
            )
        }
    }

    /// Loads the engine and, on success, records the model as the one in use.
    ///
    /// `adoptsOnlyIfUnclaimed` is for the two callers that are adopting a
    /// finished download rather than obeying a tap. Loading takes seconds, and
    /// onboarding's Continue can commit a different model inside that window;
    /// without the re-check the stored identifier ends up naming whichever
    /// engine happened to finish second, which is not what anyone chose.
    private func prepare(
        _ model: LocalModelDescriptor,
        languageOverride: String? = nil,
        adoptsOnlyIfUnclaimed: Bool = false
    ) {
        modelLoadError = nil
        modelLoadTask?.cancel()
        // A finished download is adopted the moment its files are on disk,
        // before the engine is loaded — which is how the rest of the app
        // already defines ready (`isOnDeviceReady` is "downloaded", not
        // "loaded"), and what Continue and a resumed download both do.
        //
        // Adopting only after the load left a gap of several seconds where the
        // files were there and nothing was selected, and home filled it with
        // "No speech-to-text model downloaded" right after the bar finished.
        //
        // Committed synchronously, not inside the task: a task starts on a
        // later turn, and home would still get one frame of the wrong card.
        if adoptsOnlyIfUnclaimed {
            if let inUse = LocalTranscriptionPreferences.modelIdentifier,
               inUse != model.id,
               manager.isDownloaded(inUse),
               !manager.failedIntegrityModelIDs.contains(inUse)
            {
                // Something else is the model. Nothing to adopt, and no reason
                // to load an engine nobody chose.
                return
            }
            commitSelection(model)
        }
        let commitsAfterLoad = !adoptsOnlyIfUnclaimed
        modelLoadTask = Task { @MainActor in
            do {
                let requestedLanguage = languageOverride.flatMap(TranscriptionLanguage.init(rawValue:))
                    ?? KeyboardPreferences.transcriptionLanguage
                let language = ModelLanguageSupport.resolve(
                    requestedLanguage,
                    modelLanguages: model.selectableLanguageCodes
                )
                try await manager.prepare(
                    model,
                    language: language.rawValue
                )
                guard !Task.isCancelled else { return }
                // "Use this model" is a choice the user is watching happen:
                // it commits only once the engine has actually loaded, so a
                // failed load does not quietly switch their model.
                if commitsAfterLoad { commitSelection(model) }
            } catch is CancellationError {
                // The picker does not expose cancellation for engine loading;
                // cancellation here only prevents a stale selection commit.
            } catch {
                modelLoadError = "Could not load \(model.displayName): "
                    + error.localizedDescription
            }
            modelLoadTask = nil
        }
    }

    private func commitSelection(_ model: LocalModelDescriptor) {
        LocalTranscriptionPreferences.modelIdentifier = model.id
        LocalTranscriptionPreferences.enabled = true
        onChange()
    }

    private func applyGuidance(language: String, priority: ModelGuidancePriority) {
        guidanceLanguageOverride = language
        guidancePriority = priority
        if let language = TranscriptionLanguage(rawValue: language) {
            KeyboardPreferences.transcriptionLanguage = language
        }
    }
}

/// Owns the sheet from one stable view. `LocalModelPicker` emits several
/// sibling `Section`s into its parent `List`; attaching a presentation modifier
/// to that multi-view builder creates multiple transient sheet presenters and
/// can pop onboarding back to its root instead of presenting the choices.
private struct ModelGuidanceChoiceButton: View {
    @Binding var selection: ModelGuidancePriority
    let language: String
    let deviceLanguageName: String
    let enabled: Bool
    let onApply: (String, ModelGuidancePriority) -> Void
    @State private var isPresented = false

    var body: some View {
        Button("Help me choose") {
            isPresented = true
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .frame(maxWidth: .infinity)
        .disabled(!enabled)
        .sheet(isPresented: $isPresented) {
            ModelGuidanceChoiceSheet(
                selected: selection,
                selectedLanguage: language,
                deviceLanguageName: deviceLanguageName,
                onApply: onApply
            )
        }
    }
}

private struct ModelGuidanceChoiceSheet: View {
    @Environment(\.dismiss) private var dismiss

    let selected: ModelGuidancePriority
    let selectedLanguage: String
    let deviceLanguageName: String
    let onApply: (String, ModelGuidancePriority) -> Void

    @State private var languageSelection: String
    @State private var prioritySelection: ModelGuidancePriority

    init(
        selected: ModelGuidancePriority,
        selectedLanguage: String,
        deviceLanguageName: String,
        onApply: @escaping (String, ModelGuidancePriority) -> Void
    ) {
        self.selected = selected
        self.selectedLanguage = selectedLanguage
        self.deviceLanguageName = deviceLanguageName
        self.onApply = onApply
        _languageSelection = State(initialValue: selectedLanguage)
        _prioritySelection = State(initialValue: selected)
    }

    private var languageOptions: [(code: String, name: String)] {
        var options: [(code: String, name: String)] = [
            (
                TranscriptionLanguage.automatic.rawValue,
                "Use iPhone language (\(deviceLanguageName))"
            )
        ]
        options += TranscriptionLanguage.allCases
            .filter { $0 != .automatic }
            .map { ($0.rawValue, $0.displayName) }
        if !options.contains(where: { $0.code == selectedLanguage }),
           let name = Locale.current.localizedString(forLanguageCode: selectedLanguage) {
            options.insert((selectedLanguage, name), at: 1)
        }
        return options
    }

    /// What the current answers would actually produce, recomputed as they
    /// change. Two abstract questions with no visible consequence is what made
    /// this sheet hard to answer; the preview is the answer to both.
    private var preview: ModelGuidanceResult {
        LocalModelCatalog.guidance(
            deviceMemoryGB: LocalModelCatalog.deviceMemoryGB,
            intent: ModelGuidanceIntent(
                language: languageSelection,
                priority: prioritySelection
            )
        )
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(
                        "Tell us the language you speak most and what matters most "
                            + "for the download. The match below updates as you choose."
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }

                Section("What language do you speak most?") {
                    Picker("Primary language", selection: $languageSelection) {
                        ForEach(languageOptions, id: \.code) { option in
                            Text(option.name).tag(option.code)
                        }
                    }
                }

                Section("What matters most?") {
                    ForEach(ModelGuidancePriority.allCases) { priority in
                        Button {
                            prioritySelection = priority
                        } label: {
                            HStack(alignment: .top, spacing: VocaMetrics.related + 2) {
                                Image(
                                    systemName: priority == prioritySelection
                                        ? "checkmark.circle.fill"
                                        : "circle"
                                )
                                .foregroundStyle(
                                    priority == prioritySelection ? Color.brand : .secondary
                                )
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(priority.title)
                                        .font(.headline)
                                    Text(priority.detail)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(
                            priority == prioritySelection ? [.isSelected] : []
                        )
                    }
                }

                Section("You would get") {
                    if let model = preview.model {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(model.displayName)
                                .font(.headline)
                            Text(preview.downloadDetail ?? model.sizeLabel)
                                .font(.subheadline)
                            Text(preview.reason)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text(preview.reason)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Help me choose")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use this match") {
                        onApply(languageSelection, prioritySelection)
                        dismiss()
                    }
                    .disabled(preview.model == nil)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

#if DEBUG

// MARK: - Previews

// Seven row states, three of which cannot be reached on purpose: verifying
// lasts seconds, loading needs a real ONNX graph, and failed integrity needs a
// corrupted download. All three have their own wording, and none of it had ever
// been looked at.

/// A `List` because the picker builds `Section`s, which have no meaning outside
/// one.
private struct ModelPickerPreview: View {
    let manager: LocalModelManager
    var selected: String?

    var body: some View {
        NavigationStack {
            List {
                LocalModelPicker(manager: manager, previewSelectedModelID: selected)
            }
            .navigationTitle("On-device models")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

#Preview("Models — nothing downloaded") {
    PreviewHost { ModelPickerPreview(manager: LocalModelManager(preview: [])) }
}

#Preview("Models — downloading") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [],
                downloading: PreviewFixtures.firstModelID,
                progress: 0.43
            )
        )
    }
}

#Preview("Models — verifying checksums") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [],
                verifying: [PreviewFixtures.firstModelID]
            )
        )
    }
}

#Preview("Models — failed verification") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [],
                failedIntegrity: [PreviewFixtures.firstModelID],
                message: "The downloaded files do not match their published checksums.",
                hasError: true
            )
        )
    }
}

#Preview("Models — loading the engine") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [PreviewFixtures.firstModelID],
                loading: PreviewFixtures.firstModelID,
                loadingMessage: "Building the decoder for the first time…"
            )
        )
    }
}

/// One model in use, another downloading. Other Gets stay available.
#Preview("Models — one in use, one downloading") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [PreviewFixtures.firstModelID, PreviewFixtures.secondModelID],
                downloading: PreviewFixtures.secondModelID,
                progress: 0.12
            ),
            selected: PreviewFixtures.firstModelID
        )
    }
}

#Preview("Models — ready and in use") {
    PreviewHost {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [PreviewFixtures.firstModelID, PreviewFixtures.secondModelID]
            ),
            selected: PreviewFixtures.firstModelID
        )
    }
}

/// The download row puts a label and a percentage at opposite ends of one line,
/// which is the finding this matrix makes visible.
#Preview("Models — matrix", traits: .sizeThatFitsLayout) {
    PreviewMatrix {
        ModelPickerPreview(
            manager: LocalModelManager(
                preview: [PreviewFixtures.firstModelID],
                downloading: PreviewFixtures.secondModelID,
                progress: 0.67
            ),
            selected: PreviewFixtures.firstModelID
        )
    }
}
#endif
