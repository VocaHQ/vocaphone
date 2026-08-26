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

    @State private var modelLoadTask: Task<Void, Never>?
    @State private var modelLoadError: String?
    @State private var availableModelsExpanded = false
    @State private var pendingDeletion: LocalModelDescriptor?
    @State private var guidancePriority: ModelGuidancePriority = .balanced
    @State private var showingGuidance = false

    private var usable: [LocalModelDescriptor] { LocalModelCatalog.usableOnDevice }

    private var installedModels: [LocalModelDescriptor] {
        usable.filter { manager.isDownloaded($0.id) || state(for: $0) != .notDownloaded }
    }

    private var guidance: ModelGuidanceResult {
        let language = guidanceLanguage.isEmpty
            ? LocalModelCatalog.deviceLanguage
            : guidanceLanguage
        return LocalModelCatalog.guidance(
            deviceMemoryGB: LocalModelCatalog.deviceMemoryGB,
            intent: ModelGuidanceIntent(language: language, priority: guidancePriority)
        )
    }

    private var picks: [ModelPick] {
        if onboarding {
            guard let model = guidance.model else { return [] }
            return [ModelPick(role: .guided, model: model)]
        }
        return LocalModelCatalog.recommendations(deviceMemoryGB: LocalModelCatalog.deviceMemoryGB)
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

    private func role(of model: LocalModelDescriptor) -> ModelPickRole? {
        picks.first { $0.model.id == model.id }?.role
    }

    /// The five states a model can be in, named once so every surface uses the
    /// same words for them.
    private enum ModelState: Equatable {
        case notDownloaded
        case downloading
        case verifying
        case failedIntegrity
        case loading
        case ready
        case selected

        var label: String {
            switch self {
            case .notDownloaded: "Not downloaded"
            case .downloading: "Downloading"
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
            case .downloading, .verifying, .loading: .working
            case .failedIntegrity: .failed
            case .ready, .selected: .ready
            }
        }
    }

    private func state(for model: LocalModelDescriptor) -> ModelState {
        if manager.downloadingModelID == model.id { return .downloading }
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
                .sheet(isPresented: $showingGuidance) {
                    ModelGuidanceChoiceSheet(
                        selected: guidancePriority,
                        onSelect: {
                            guidancePriority = $0
                            showingGuidance = false
                        },
                        onDismiss: { showingGuidance = false }
                    )
                }
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
        if usable.isEmpty {
            Section {
                Text("No on-device model fits this iPhone yet.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } else if let model = guidance.model {
            Section {
                Text("We picked one model that fits this iPhone and your language.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text("Works with \(guidance.languageName) · \(model.sizeLabel) download")
                    .font(.subheadline)
                Text(guidance.reason)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                row(for: model, onboarding: true)
                Button("Help me choose") {
                    showingGuidance = true
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            } header: {
                Text("Recommended for you")
            } footer: {
                Text("You can change the model later in Settings. The model name and engine are shown above.")
            }

            let otherInstalled = installedModels.filter { $0.id != model.id }
            if !otherInstalled.isEmpty {
                Section("Already on this iPhone") {
                    ForEach(otherInstalled) { installed in
                        row(for: installed)
                    }
                }
            }

            Section("More compatible models") {
                if availableModels.isEmpty {
                    Text("All other compatible models are already on this iPhone.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    DisclosureGroup(isExpanded: $availableModelsExpanded) {
                        ForEach(availableModels) { available in
                            row(for: available)
                        }
                    } label: {
                        Text("Browse \(availableModels.count) compatible models")
                    }
                }
            }
        } else {
            Section {
                Text(guidance.reason)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text("Choose another language in Dictation settings, or use your self-hosted gateway.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("No guided match")
            }
            Section("Compatible models") {
                ForEach(usable) { model in
                    row(for: model)
                }
            }
        }
    }

    private func row(for model: LocalModelDescriptor, onboarding: Bool = false) -> some View {
        let state = state(for: model)
        return VStack(alignment: .leading, spacing: VocaMetrics.related + 2) {
            VocaStatusLine(
                status: state.status,
                title: onboarding ? "Your match" : model.displayName,
                detail: onboarding
                    ? "\(model.displayName) · \(model.sizeLabel) · \(model.languages)"
                    : detail(for: model, state: state)
            )

            switch state {
            case .downloading:
                VStack(alignment: .leading, spacing: VocaMetrics.related) {
                    HStack {
                        Text("Downloading")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(Int(manager.progress * 100))%")
                            .font(.subheadline.monospacedDigit())
                    }
                    ProgressView(value: manager.progress)
                    Button("Cancel") {
                        manager.cancelDownload()
                    }
                    .buttonStyle(.bordered)
                }
            case .verifying, .loading:
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
            // `.frame(maxWidth:)` outside a button stretches its *layout* and
            // leaves the control its natural size, which is how these ended up
            // the same compact pill as the destructive action below them. The
            // width belongs on the label.
            case .failedIntegrity:
                Button {
                    onboarding ? downloadAndUse(model) : download(model)
                } label: {
                    Text("Download again").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            case .notDownloaded:
                Button {
                    onboarding ? downloadAndUse(model) : download(model)
                } label: {
                    Text(
                        onboarding
                            ? "Download and continue · \(model.sizeLabel)"
                            : "Download \(model.sizeLabel)"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(isBusy)
            case .ready:
                VocaPrimaryButton(title: "Use this model") { prepare(model) }
                    .disabled(isBusy)
            case .selected:
                EmptyView()
            }

            if state == .ready || state == .selected || state == .failedIntegrity {
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
        .padding(.vertical, VocaMetrics.tight)
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

    private var isBusy: Bool {
        manager.downloadingModelID != nil || manager.loadingModelID != nil
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

    private func download(_ model: LocalModelDescriptor) {
        manager.startDownload(model) {
            onChange()
        }
    }

    private func downloadAndUse(_ model: LocalModelDescriptor) {
        manager.startDownload(model) {
            guard manager.isDownloaded(model.id) else {
                onChange()
                return
            }
            prepare(model)
        }
    }

    private func prepare(_ model: LocalModelDescriptor) {
        modelLoadError = nil
        modelLoadTask?.cancel()
        modelLoadTask = Task { @MainActor in
            do {
                try await manager.prepare(
                    model,
                    language: KeyboardPreferences.effectiveTranscriptionLanguage.rawValue
                )
                guard !Task.isCancelled else { return }
                LocalTranscriptionPreferences.modelIdentifier = model.id
                LocalTranscriptionPreferences.enabled = true
                onChange()
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
}

private struct ModelGuidanceChoiceSheet: View {
    let selected: ModelGuidancePriority
    let onSelect: (ModelGuidancePriority) -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(
                        "Your language stays the same. Choose whether the first download "
                            + "should favour a balanced match, a smaller download, or "
                            + "measured accuracy when that comparison is available."
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }

                Section("What matters most?") {
                    ForEach(ModelGuidancePriority.allCases) { priority in
                        Button {
                            onSelect(priority)
                        } label: {
                            HStack(alignment: .top, spacing: VocaMetrics.related + 2) {
                                Image(
                                    systemName: priority == selected
                                        ? "checkmark.circle.fill"
                                        : "circle"
                                )
                                .foregroundStyle(
                                    priority == selected ? Color.brand : .secondary
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
                    }
                }
            }
            .navigationTitle("Help me choose")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done", action: onDismiss)
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

/// One model in use, another ready beside it — and every other Download button
/// disabled by `isBusy` with nothing on screen saying why.
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
