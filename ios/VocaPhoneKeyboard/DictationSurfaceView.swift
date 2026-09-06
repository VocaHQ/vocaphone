import SwiftUI
import UIKit

// MARK: - State Observable

final class DictationSurfaceState: ObservableObject {
    private var storedState: SessionState = .idle
    var state: SessionState {
        get { storedState }
        set { change(&storedState, to: newValue) }
    }
    @Published var meterLevels: [Float] = []
    private var storedLanguage: TranscriptionLanguage = KeyboardPreferences.effectiveTranscriptionLanguage
    var language: TranscriptionLanguage {
        get { storedLanguage }
        set { change(&storedLanguage, to: newValue) }
    }
    private var storedStyle: WritingStyle = KeyboardPreferences.writingStyle
    var style: WritingStyle {
        get { storedStyle }
        set { change(&storedStyle, to: newValue) }
    }
    private var storedShowsGlobeKey: Bool = false
    var showsGlobeKey: Bool {
        get { storedShowsGlobeKey }
        set { change(&storedShowsGlobeKey, to: newValue) }
    }
    /// What the typing engine is offering right now, kept in its own object.
    ///
    /// Every keystroke replaces this list. Published on *this* object, that
    /// invalidated the whole surface — glass buttons, waveform, menus — on each
    /// letter, on the main thread, inside an extension with about fifty
    /// megabytes to its name. Typing fast felt like typing through treacle for
    /// exactly that reason. In its own object, a keystroke redraws the row of
    /// words and nothing else.
    let typing = TypingRowState()

    /// Whether there is anything to suggest. Separate from the list because the
    /// surface's own layout only cares about empty or not, and that answer
    /// changes once a word rather than once a letter.
    @Published private(set) var hasCandidates = false

    var candidates: [TypingCandidate] {
        get { typing.candidates }
        set {
            typing.candidates = newValue
            let has = !newValue.isEmpty
            if has != hasCandidates { hasCandidates = has }
        }
    }
    /// What the centre says instead of the language and style line: the state's
    /// own words, from the same place the old bar took them. "Gateway
    /// unavailable", "Nothing was recognised" and the rest are the product's
    /// copy, and the surface has no business inventing a second wording.
    private var storedCenterMessage: String? = nil
    var centerMessage: String? {
        get { storedCenterMessage }
        set { change(&storedCenterMessage, to: newValue) }
    }
    /// The trailing button in this state: Finish, Insert, Retry, Start.
    private var storedPrimarySymbol: String = "mic.fill"
    var primarySymbol: String {
        get { storedPrimarySymbol }
        set { change(&storedPrimarySymbol, to: newValue) }
    }
    private var storedPrimaryIsEnabled: Bool = true
    var primaryIsEnabled: Bool {
        get { storedPrimaryIsEnabled }
        set { change(&storedPrimaryIsEnabled, to: newValue) }
    }
    /// The spring every phase change of this surface uses.
    ///
    /// Published so the keyboard lab can drive it from two sliders: how a
    /// transition feels is not a number anybody picks correctly by reasoning
    /// about it, and the loop of guess → build → install → press is a minute
    /// long.
    ///
    /// They persist to the App Group, so a pair settled on in the lab is the
    /// pair the keyboard extension uses the next time it appears — the point is
    /// to feel it on the real keyboard, not only in a preview. Nothing in a
    /// shipping build writes them: the lab is the only writer, and it is
    /// debug-only.
    @Published var animationResponse: Double = KeyboardPreferences.surfaceAnimationResponse {
        didSet { KeyboardPreferences.surfaceAnimationResponse = animationResponse }
    }
    @Published var animationDamping: Double = KeyboardPreferences.surfaceAnimationDamping {
        didSet { KeyboardPreferences.surfaceAnimationDamping = animationDamping }
    }
    private var storedIsDark: Bool = false
    var isDark: Bool {
        get { storedIsDark }
        set { change(&storedIsDark, to: newValue) }
    }

    /// One place for the buzz, and only for typing.
    ///
    /// The three round controls do not buzz: a tap that starts or ends a
    /// recording already answers with the whole surface changing, and a second
    /// confirmation in the hand is noise. Choosing a suggestion has no such
    /// answer — the word simply appears in somebody else's text field — so that
    /// one keeps its tap.
    ///
    /// It answers to the keyboard's own haptics setting, the one already in
    /// Settings, rather than a second switch of its own. Two switches for one
    /// sensation is how somebody turns haptics off and still feels the
    /// keyboard buzz.
    func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        guard KeyboardPreferences.typingHapticsEnabled else { return }
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    /// Announces a change only when there is one.
    ///
    /// `@Published` announces on every assignment, changed or not — and the
    /// render below assigns nine of these in a row, on a path it takes twice a
    /// word: once when the suggestions go on a space, and once when they come
    /// back on the next letter. Each announcement rebuilds this whole surface,
    /// glass and menus and waveform included. Measured on device: 39 rebuilds
    /// of everything per 95 keystrokes, at 7.5 ms apiece against a 16.6 ms
    /// frame, in a process with fifty megabytes to its name.
    private func change<T: Equatable>(_ storage: inout T, to value: T) {
        guard storage != value else { return }
        objectWillChange.send()
        storage = value
    }

    var onStart: (() -> Void)?
    var onFinish: (() -> Void)?
    var onCancel: (() -> Void)?
    var onLanguageChanged: ((TranscriptionLanguage) -> Void)?
    var onStyleChanged: ((WritingStyle) -> Void)?
    var onGlobe: (() -> Void)?
    var onCandidate: ((TypingCandidate) -> Void)? {
        get { typing.onCandidate }
        set { typing.onCandidate = newValue }
    }
    /// Whatever the trailing button means right now.
    var onPrimary: (() -> Void)?

    init() {}

    /// Re-reads both preferences.
    ///
    /// They live in the App Group, and the app's own Settings screen writes the
    /// same two keys — so a language changed in the app while the keyboard was
    /// on screen has to land here, or the line under the waveform starts naming
    /// a setting that is no longer in force.

    func refreshPreferences() {
        language = KeyboardPreferences.effectiveTranscriptionLanguage
        style = KeyboardPreferences.writingStyle
    }

    /// Persists the choice, exactly as the dictation bar's own menu does.
    ///
    /// `effectiveTranscriptionLanguage` rather than the raw choice: a language
    /// the loaded model cannot be asked for resolves back to Automatic, and the
    /// line under the waveform must say what will actually happen.
    func select(language newLanguage: TranscriptionLanguage) {
        KeyboardPreferences.transcriptionLanguage = newLanguage
        KeyboardPreferences.noteTranscriptionLanguageUse(newLanguage)
        language = KeyboardPreferences.effectiveTranscriptionLanguage
        onLanguageChanged?(newLanguage)
    }

    func select(style newStyle: WritingStyle) {
        KeyboardPreferences.writingStyle = newStyle
        style = newStyle
        onStyleChanged?(newStyle)
    }

    /// Automatic, then the languages this person actually uses, then the rest.
    ///
    /// The shortcuts are filtered by what the loaded model can be asked for: a
    /// greyed-out row is worse than no row when only about five of them fit.
    var languageShortcuts: [TranscriptionLanguage] {
        let modelLanguages = KeyboardPreferences.activeModelLanguages
        let recents = KeyboardPreferences.recentTranscriptionLanguages.filter {
            $0 != .automatic
                && ModelLanguageSupport.isSelectable($0, modelLanguages: modelLanguages)
        }
        var shortcuts: [TranscriptionLanguage] = [.automatic] + recents
        // The current selection always deserves a row, even if it was never
        // recorded as recent: it is the one entry being looked for.
        if language != .automatic, !shortcuts.contains(language) {
            shortcuts.append(language)
        }
        return shortcuts
    }

    var remainingLanguages: [TranscriptionLanguage] {
        let shortcuts = Set(languageShortcuts)
        return TranscriptionLanguage.allCases.filter { !shortcuts.contains($0) }
    }

    func isSelectable(_ candidate: TranscriptionLanguage) -> Bool {
        ModelLanguageSupport.isSelectable(
            candidate,
            modelLanguages: KeyboardPreferences.activeModelLanguages
        )
    }
}

/// The suggestion row's own state, so a keystroke does not redraw a keyboard.
final class TypingRowState: ObservableObject {
    @Published var candidates: [TypingCandidate] = []
    @Published var isDark = false
    var onCandidate: ((TypingCandidate) -> Void)?
    var hapticsEnabled = true

    func tap() {
        guard KeyboardPreferences.typingHapticsEnabled else { return }
        UIImpactFeedbackGenerator(style: KeyboardPreferences.typingHapticStyle.feedbackStyle)
            .impactOccurred(intensity: KeyboardPreferences.typingHapticIntensity)
    }
}

// MARK: - DictationSurfaceView

struct DictationSurfaceView: View {
    @ObservedObject var state: DictationSurfaceState
    @Namespace private var animationNamespace

    private static let brandGreen = Color(red: 13 / 255, green: 104 / 255, blue: 77 / 255)
    /// The diameter the rest of the product already uses for a round control:
    /// `VocaMetrics.minimumTarget`, which is also the HIG minimum. Named again
    /// rather than imported because the design system is compiled into the app
    /// and this view has to build inside the keyboard extension too.
    ///
    /// 60 was picked by eye from a mockup and came out a third larger than the
    /// toolbar buttons on the app's own home screen.
    private static let buttonDiameter: CGFloat = 44
    /// The air around the control row.
    ///
    /// Ten above and nothing below sat the buttons on the keys; six and four
    /// puts the row in the middle of its strip. The sides matter for the same
    /// reason — the keyboard's own inset is six points, and a round control
    /// against that edge reads as falling off it.
    private static let topInset: CGFloat = 6
    private static let bottomInset: CGFloat = 4
    private static let sideInset: CGFloat = 6
    /// Glyphs at the toolbar's own weight and size, for the same reason.
    private static let glyphSize: CGFloat = 17

    init(state: DictationSurfaceState) {
        self.state = state
    }

    private var isRecording: Bool {
        state.state == .recording
    }

    /// Recording is over and the transcript is not back yet.
    ///
    /// This is a phase of the same surface, not a different screen. Handing the
    /// session back to another layout the instant the check is tapped throws
    /// away the one thing the person is watching — and they tapped it half a
    /// second ago.
    private var isWorking: Bool {
        switch state.state {
        case .launchingApp, .awaitingReturn: true
        case .finalizing, .uploading, .transcribing: true
        default: false
        }
    }

    /// The one line under the middle of the surface.
    private var centerText: String {
        if let message = state.centerMessage, !isRecording { return message }
        if isWorking { return workingTitle }
        return "\(state.language.displayName) • \(state.style.displayName)"
    }

    /// What the wait is called. Starting and finishing are both waits, and both
    /// are this surface holding still — but they are not the same wait, and a
    /// surface that said "Transcribing" before a word was spoken would be
    /// lying about which one it is.
    private var workingTitle: String {
        switch state.state {
        case .launchingApp, .awaitingReturn: "Starting"
        default: "Transcribing"
        }
    }

    /// A state the surface has to explain rather than just offer: a failure, a
    /// transcript waiting to go in, a field that went away.
    private var hasMessage: Bool { state.centerMessage != nil }

    /// Whether the surface stands open: the tall layout with a centre.
    private var isOpen: Bool { isRecording || isWorking || hasMessage }

    /// Typing, with something to offer. The two menu buttons collapse into one
    /// so the row they were using becomes the suggestions — which is what the
    /// hand needs while typing, and the menus are one tap away in it.
    private var isSuggesting: Bool { !isOpen && state.hasCandidates }

    private var surfaceSpring: Animation {
        .spring(response: state.animationResponse, dampingFraction: state.animationDamping)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top Controls Bar (Top row of both Idle and Recording)
            HStack(spacing: 8) {
                if #available(iOS 26, *) {
                    GlassEffectContainer(spacing: 8) {
                        HStack(spacing: 8) {
                            leadingGlassGroup
                        }
                    }
                } else {
                    HStack(spacing: 8) {
                        leadingGlassGroup
                    }
                }

                if isSuggesting {
                    CandidateRow(state: state.typing)
                } else {
                    Spacer()
                }

                trailingActionButton
            }
            // No fixed height. `GlassEffectContainer` lays out taller than its
            // content — it reserves room for the glass to spill and merge — and
            // pinning the row to 44 pt made that surplus appear as space above
            // and below the buttons, which is what pushed them off the top.
            // The row is the first thing in the stack; that is what puts it at
            // the top, not a height.

            if isOpen {
                Spacer(minLength: 0)

                // Center Waveform and Subtitle
                VStack(spacing: 16) {
                    if hasMessage, !isRecording {
                        // Nothing to meter: the bars would be decoration on top
                        // of a sentence the reader needs to actually read.
                        EmptyView()
                    } else {
                        // One waveform across both phases, not two swapped for
                        // one another. Two of them are two identities to
                        // SwiftUI: it removes one and inserts the other, and
                        // the row relaxes and re-expands around the gap. What
                        // changes on finishing is the *input* — the bars are
                        // held, because there is no audio any more and bars
                        // that kept moving would say the microphone is open.
                        LiveWaveformBars(
                            levels: isWorking ? [] : state.meterLevels,
                            tint: isWorking
                                ? Self.brandGreen.opacity(0.35)
                                : Self.brandGreen
                        )
                        .frame(height: 48)
                    }

                    Text(centerText)
                        .font(.system(size: 15, weight: .regular))
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .padding(.horizontal, 24)
                        .foregroundStyle(state.isDark ? Color(white: 0.65) : Color(white: 0.45))
                        // A reserved line, so swapping one sentence for another
                        // does not resize the block the waveform is sitting on.
                        // "Automatic • Clean" and "Transcribing" are not the
                        // same width, and without this the row re-centres
                        // around the difference.
                        .frame(minHeight: 20)
                        .animation(nil, value: centerText)
                }
                .transition(.asymmetric(
                    insertion: .scale(scale: 0.88).combined(with: .opacity),
                    removal: .opacity
                ))

                Spacer()

                // Bottom row for system globe key if needed
                if state.showsGlobeKey {
                    HStack {
                        Button {
                            state.onGlobe?()
                        } label: {
                            Image(systemName: "globe")
                                .font(.system(size: 20))
                                .foregroundStyle(state.isDark ? Color.white : Color.primary)
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        Spacer()
                    }
                    .transition(.opacity)
                }
            } else {
                Spacer(minLength: 0)
            }
        }
        // Deliberate air above the controls, rather than whatever the glass
        // container happened to reserve. One number, in one place, so it stays
        // the same in every phase.
        .padding(.top, Self.topInset)
        .padding(.bottom, Self.bottomInset)
        .padding(.horizontal, Self.sideInset)
        .onAppear {
            state.refreshPreferences()
        }
        // Hung from the top. The controls are in the same place in every phase,
        // which is the point of keeping one surface: the finger that just
        // tapped the check knows where Cancel is without looking for it.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // Fast, and barely springy. A keyboard control answers the finger; a
        // settle of a third of a second reads as the keyboard thinking about
        // it. Tunable from the lab — see ``DictationSurfaceState/animationResponse``.
        .animation(surfaceSpring, value: isOpen)
        .animation(surfaceSpring, value: isWorking)
        .animation(surfaceSpring, value: isSuggesting)
    }

    // MARK: - Leading Glass Buttons

    @ViewBuilder
    private var leadingGlassGroup: some View {
        if isOpen {
            cancelGlassButton
        } else {
            languageMenuButton
            styleMenuButton
        }
    }

    @ViewBuilder
    private var cancelGlassButton: some View {
        Button {
            state.onCancel?()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: Self.glyphSize, weight: .semibold))
                .foregroundStyle(state.isDark ? Color.white : Color.black)
                .frame(width: Self.buttonDiameter, height: Self.buttonDiameter)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .modifier(GlassButtonModifier(id: "leadGlass", namespace: animationNamespace))
    }

    /// The two menus, collapsed into one while the row is carrying suggestions.
    /// Same glass, same place, same `glassEffectID` — so it is the two buttons
    /// merging rather than three buttons swapping.
    @ViewBuilder
    private var menusGlassButton: some View {
        Menu {
            Menu("Language") {
                ForEach(state.languageShortcuts, id: \.self) { lang in
                    languageButton(lang)
                }
                Menu("More languages") {
                    ForEach(state.remainingLanguages, id: \.self) { lang in
                        languageButton(lang)
                    }
                }
            }
            Menu("Style") {
                ForEach(WritingStyle.allCases, id: \.self) { item in
                    Button {
                        state.select(style: item)
                    } label: {
                        HStack {
                            Text(item.displayName)
                            if item == state.style {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: Self.glyphSize, weight: .semibold))
                .foregroundStyle(state.isDark ? Color.white : Color.black)
                .frame(width: Self.buttonDiameter, height: Self.buttonDiameter)
                .contentShape(Circle())
        }
        .modifier(GlassButtonModifier(id: "leadGlass", namespace: animationNamespace))
    }

    @ViewBuilder
    private var languageMenuButton: some View {
        Menu {
            ForEach(state.languageShortcuts, id: \.self) { lang in
                languageButton(lang)
            }
            Menu("More languages") {
                ForEach(state.remainingLanguages, id: \.self) { lang in
                    languageButton(lang)
                }
            }
        } label: {
            Image(systemName: "globe")
                .font(.system(size: Self.glyphSize, weight: .semibold))
                .foregroundStyle(state.isDark ? Color.white : Color.black)
                .frame(width: Self.buttonDiameter, height: Self.buttonDiameter)
                .contentShape(Circle())
        }
        .modifier(GlassButtonModifier(id: "leadGlass", namespace: animationNamespace))
    }

    @ViewBuilder
    private var styleMenuButton: some View {
        Menu {
            // A `Picker` rather than a row of buttons: iOS draws the tick on
            // the chosen row itself, which frees the row's own image for the
            // style's icon. Hand-rolled buttons had to spend that image on the
            // tick, so the menu listed six styles with no icons at all — the
            // same six icons the button beside it is drawn from.
            // Toggles rather than a `Picker`, and this is the whole reason the
            // button's icon lagged a beat behind the choice: a picker inside a
            // menu commits its selection when the menu *dismisses*, not when
            // the row is tapped. The old style stayed on screen for the length
            // of that animation because it was still the truth.
            //
            // A toggle fires on the tap, and keeps what the picker was chosen
            // for: iOS draws the tick itself, so the row's image stays free for
            // the style's own icon.
            ForEach(WritingStyle.allCases, id: \.self) { item in
                Toggle(isOn: styleBinding(for: item)) {
                    Label(item.displayName, systemImage: item.symbolName)
                }
            }
        } label: {
            // The button shows the style that is in force. Three things used
            // to make it show it late, and all three are gone: the picker that
            // committed on dismissal rather than on the tap, the second writer
            // in the controller, and the poll that reassigned the value several
            // times a second and could put a stale one back.
            Image(systemName: state.style.symbolName)
                .font(.system(size: Self.glyphSize, weight: .semibold))
                .foregroundStyle(state.isDark ? Color.white : Color.black)
                .frame(width: Self.buttonDiameter, height: Self.buttonDiameter)
                .contentShape(Circle())
        }
        .modifier(GlassButtonModifier(id: "styleGlass", namespace: animationNamespace))
        .accessibilityLabel("Writing style")
        .accessibilityValue(state.style.displayName)
    }

    /// Writes through ``DictationSurfaceState/select(style:)`` so the choice is
    /// persisted, rather than only changing what the menu shows.
    /// Turning a style off is not a thing — one of the six is always in force —
    /// so only the on direction does anything.
    private func styleBinding(for style: WritingStyle) -> Binding<Bool> {
        Binding(
            get: { state.style == style },
            set: { isOn in if isOn { state.select(style: style) } }
        )
    }

    /// One row of the language menu. Disabled where the loaded model cannot be
    /// asked for it, which is the same rule the dictation bar's menu applies.
    @ViewBuilder
    private func languageButton(_ lang: TranscriptionLanguage) -> some View {
        Button {
            state.select(language: lang)
        } label: {
            HStack {
                Text(lang.displayName)
                if lang == state.language {
                    Image(systemName: "checkmark")
                }
            }
        }
        .disabled(!state.isSelectable(lang))
    }

    // MARK: - Trailing Primary Action Button (Green Circle)

    private var trailingActionButton: some View {
        Button {
            // Finish, Insert, Retry, Start: the state decides, the surface
            // draws it. `onPrimary` is set for every state the controller
            // drives; the two below are what the lab and the previews use.
            if let onPrimary = state.onPrimary {
                onPrimary()
            } else if isRecording {
                state.onFinish?()
            } else {
                state.onStart?()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(Self.brandGreen)
                    .frame(width: Self.buttonDiameter, height: Self.buttonDiameter)
                    .shadow(color: Self.brandGreen.opacity(0.35), radius: 4, x: 0, y: 2)

                if isWorking {
                    // The same circle, still there, no longer an action: the
                    // work it started is what it is now reporting.
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Image(systemName: isRecording ? "checkmark" : state.primarySymbol)
                        .font(.system(size: Self.glyphSize, weight: .semibold))
                        .foregroundStyle(.white)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .buttonStyle(.plain)
        // Tapping through a transcription would start a second recording over
        // the top of the first. Cancel stays live; this does not.
        .disabled(isWorking || !state.primaryIsEnabled)
        .accessibilityLabel(isWorking ? "Transcribing" : (isRecording ? "Finish" : "Start dictation"))
        .matchedGeometryEffect(id: "trailingActionCircle", in: animationNamespace)
    }
}

/// The suggestion row.
///
/// Its own view on purpose: it observes ``TypingRowState`` and nothing else, so
/// the letter-by-letter churn of candidates redraws these words and leaves the
/// glass, the waveform and the menus alone.
private struct CandidateRow: View {
    @ObservedObject var state: TypingRowState

    private static let rowHeight: CGFloat = 44

    var body: some View {
        // Scrolls rather than squeezes. Dividing the row equally meant every
        // suggestion got the same width whatever it was, so a long word came
        // out as "correspond…" while a two-letter one sat in a puddle of space.
        // A word the reader cannot finish reading is not a suggestion.
        ScrollView(.horizontal) {
            row
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        // The fade says "there is more this way" without a scrollbar, which on
        // a strip this short would be most of the strip.
        .mask {
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black, location: 0.04),
                    .init(color: .black, location: 0.96),
                    .init(color: .clear, location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        }
        .frame(maxWidth: .infinity)
    }

    private var row: some View {
        HStack(spacing: 0) {
            ForEach(Array(state.candidates.enumerated()), id: \.offset) { index, candidate in
                if index > 0 {
                    Divider()
                        .frame(height: 20)
                        .padding(.horizontal, 2)
                }
                Button {
                    state.tap()
                    state.onCandidate?(candidate)
                } label: {
                    Text(Self.title(for: candidate))
                        .font(.system(
                            size: 17,
                            weight: candidate.isEmphasised ? .semibold : .regular
                        ))
                        .foregroundStyle(state.isDark ? Color.white : Color.black)
                        .lineLimit(1)
                        // No truncation and no equal shares: each word takes
                        // the width it needs and the row scrolls.
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 14)
                        .frame(minHeight: Self.rowHeight)
                        .background {
                            // The one the keyboard will apply when you press
                            // space still has to be tellable from the rest —
                            // without it the row is five identical words and no
                            // sign which one has already been chosen for you.
                            Capsule()
                                .fill(state.isDark ? Color.white.opacity(0.14) : Color.white)
                                .opacity(candidate.isEmphasised ? 1 : 0)
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(CandidateButtonStyle(isDark: state.isDark))
            }
        }
    }

    /// The literal and the revert are quoted, exactly as the system keyboard
    /// quotes a word it is about to take away or has just taken. The revert
    /// carries the undo arrow too: by the time it appears the replacement is
    /// already in the document, so the chip has to say "put it back".
    private static func title(for candidate: TypingCandidate) -> String {
        switch candidate.kind {
        case .literal: "\u{201C}\(candidate.text)\u{201D}"
        case .revert: "\u{21A9} \u{201C}\(candidate.text)\u{201D}"
        default: candidate.text
        }
    }
}

/// A suggestion chip. Flat until it is touched, then a solid capsule — the
/// press has to read on a surface that has no fill of its own.
private struct CandidateButtonStyle: ButtonStyle {
    let isDark: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                // Full height, matching the round controls in the same row: a
                // chip inset from its own tap target looks like a smaller
                // thing than the one the finger actually hits.
                Capsule()
                    .fill(isDark ? Color.white.opacity(0.16) : Color.white)
                    .opacity(configuration.isPressed ? 1 : 0)
            }
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Glass Button Modifier

private struct GlassButtonModifier: ViewModifier {
    let id: String
    let namespace: Namespace.ID

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content
                .glassEffect(.regular.interactive(), in: .circle)
                .glassEffectID(id, in: namespace)
        } else {
            content
                .background(.ultraThinMaterial, in: Circle())
                .overlay {
                    Circle()
                        .stroke(Color.white.opacity(0.35), lineWidth: 0.75)
                }
                .shadow(color: Color.black.opacity(0.12), radius: 3, x: 0, y: 1.5)
        }
    }
}

// MARK: - Live Waveform (9 Bars)

/// Nine bars that breathe with the microphone.
///
/// **This is decoration, and deliberately so.** ``derivedHeights`` multiplies
/// every level by a fixed envelope — 0.45 at the ends, 1.0 in the middle — so
/// the row is always a spindle whatever is said into it. Speak at one steady
/// volume and the outer bars still cannot rise past 45%: that shape is drawn,
/// not measured.
///
/// It is left in on purpose. A shaped ribbon that is obviously an ornament is
/// honest in a way a fake meter is not — Voicenotes ships the same thing, and
/// nobody is misled, because nobody reads it as an instrument. The line it must
/// not cross is *pretending*: the previous waveform invented three bars out of
/// every four and presented them as the voice, and that is what read as slop.
///
/// The measurement it decorates is real and lives elsewhere: `AudioCapture-
/// Pipeline` splits each quarter-second buffer into five, so twenty true levels
/// a second reach this view, and the bars only move when audio does. Remove the
/// envelope and this becomes a meter; keep it and it stays an ornament driven
/// by real sound. Both are defensible. Silently drifting between them is not.
struct LiveWaveformBars: View {
    let levels: [Float]
    let tint: Color

    private static let barCount = 15
    private static let barWidth: CGFloat = 5
    private static let barSpacing: CGFloat = 5
    private static let cornerRadius: CGFloat = 2.5
    private static let minHeight: CGFloat = 8
    private static let maxHeight: CGFloat = 46

    init(levels: [Float], tint: Color) {
        self.levels = levels
        self.tint = tint
    }

    var body: some View {
        HStack(spacing: Self.barSpacing) {
            ForEach(0..<Self.barCount, id: \.self) { index in
                RoundedRectangle(cornerRadius: Self.cornerRadius)
                    .fill(tint)
                    .frame(width: Self.barWidth, height: height(for: index))
            }
        }
        .animation(.spring(response: 0.18, dampingFraction: 0.75), value: derivedHeights)
    }

    private var derivedHeights: [CGFloat] {
        // The envelope, stretched across however many bars there are rather
        // than written out by hand: adding bars used to mean editing a literal
        // and getting the shape subtly wrong.
        let weights: [CGFloat] = (0..<Self.barCount).map { index in
            let position = CGFloat(index) / CGFloat(max(Self.barCount - 1, 1))
            return 0.45 + 0.55 * sin(position * .pi)
        }
        let recent = levels.suffix(Self.barCount)
        let baseLevel: CGFloat = recent.isEmpty ? 0 : CGFloat(recent.reduce(0, +) / Float(recent.count))

        return (0..<Self.barCount).map { i in
            let sample = i < recent.count ? CGFloat(Array(recent)[i]) : baseLevel
            // Speech at a normal distance sits low in the 0-1 range, so a
            // straight mapping leaves the row nearly flat until somebody
            // shouts. The curve lifts the quiet end and leaves the loud end
            // where it is.
            let effective = pow(max(sample, baseLevel * 0.7), 0.55)
            let rawHeight = Self.minHeight + (Self.maxHeight - Self.minHeight) * effective * weights[i]
            return min(max(rawHeight, Self.minHeight), Self.maxHeight)
        }
    }

    private func height(for index: Int) -> CGFloat {
        let heights = derivedHeights
        guard index < heights.count else { return Self.minHeight }
        return heights[index]
    }
}
