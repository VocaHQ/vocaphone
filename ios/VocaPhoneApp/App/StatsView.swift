import SwiftUI

struct StatsView: View {
    @State private var stats = UsageStats()
    @State private var now = Date()
    @State private var confirmingReset = false
    @State private var shareState = ShareState.idle
    @State private var shareStateToken = 0
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private let store = UsageStatsStore.shared

    private enum ShareState: Equatable {
        case idle
        case copied
        case opened(StatsShareDestination)
        case failed(String)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: VocaMetrics.grouping) {
                hero
                statGrid
                recentActivityCard
                if stats.hasAny { shareCard }
                privacyNote
                if stats.hasAny {
                    VocaDestructiveButton(title: "Reset statistics") {
                        confirmingReset = true
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, VocaMetrics.related)
                }
            }
            .padding(.horizontal, VocaMetrics.padding)
            .padding(.vertical, VocaMetrics.grouping)
        }
        .background(Color.vocaCanvas)
        .navigationTitle("Stats")
        .navigationBarTitleDisplayMode(.inline)
        .task { refresh(folding: true) }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in
            refresh(folding: false)
        }
        .onReceive(
            NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)
        ) { _ in
            refresh(folding: false)
        }
        .confirmationDialog(
            StatsCopy.resetTitle,
            isPresented: $confirmingReset,
            titleVisibility: .visible
        ) {
            Button(StatsCopy.resetConfirm, role: .destructive) { reset() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(StatsCopy.resetBody)
        }
    }

    private var hero: some View {
        VocaCard(padding: VocaMetrics.grouping) {
            VStack(alignment: .leading, spacing: VocaMetrics.padding) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: VocaMetrics.tight) {
                        Text("YOUR VOICE, IN NUMBERS")
                            .font(.caption.weight(.bold))
                            .tracking(1.3)
                            .foregroundStyle(Color.brand)
                        Text(stats.hasAny ? "Keep the momentum" : "Start your story")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(Color.vocaPrimaryText)
                    }
                    Spacer()
                    Image(systemName: stats.hasAny ? "waveform.circle.fill" : "sparkles")
                        .font(.system(size: 38))
                        .foregroundStyle(Color.brand)
                        .accessibilityHidden(true)
                }

                if stats.hasAny {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .firstTextBaseline, spacing: VocaMetrics.related) {
                            speedValue
                            Spacer()
                            streakPill
                        }
                        VStack(alignment: .leading, spacing: VocaMetrics.related) {
                            speedValue
                            streakPill
                        }
                    }
                    Text(StatsCopy.speedCaption)
                        .font(.subheadline)
                        .foregroundStyle(Color.vocaSecondaryText)
                } else {
                    Text("Complete a dictation from the VocaPhone keyboard and your private progress will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(Color.vocaSecondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    Label("Only inserted dictations count", systemImage: "checkmark.seal.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.brand)
                }
            }
        }
    }

    private var speedValue: some View {
        HStack(alignment: .firstTextBaseline, spacing: VocaMetrics.related) {
            Text(StatsFormat.wordsPerMinute(stats.averageWordsPerMinute))
                .font(.system(size: 48, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(Color.vocaPrimaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
            Text("WPM")
                .font(.headline)
                .foregroundStyle(Color.vocaSecondaryText)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "Speaking speed, \(StatsFormat.wordsPerMinute(stats.averageWordsPerMinute)) words per minute"
        )
    }

    private var streakPill: some View {
        let streak = stats.currentStreak(at: now)
        return Label(StatsFormat.streak(streak), systemImage: "flame.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(streak > 0 ? Color.vocaStatSymbol(.streak) : Color.vocaSecondaryText)
            .padding(.horizontal, 12)
            .frame(minHeight: VocaMetrics.minimumTarget)
            .background(Color.vocaRecessedSurface, in: Capsule())
            .accessibilityLabel("Current streak, \(StatsFormat.streak(streak))")
    }

    private var statGrid: some View {
        VStack(alignment: .leading, spacing: VocaMetrics.related) {
            VocaSectionHeader(title: "Lifetime")
            LazyVGrid(columns: statColumns, spacing: VocaMetrics.related) {
                statCard("text.alignleft", .words, StatsFormat.count(stats.totalWords), "Words")
                statCard("waveform", .dictations, StatsFormat.count(stats.totalDictations), "Sessions")
                statCard("clock", .time, StatsFormat.duration(stats.totalSeconds), "Voice time")
                streakCard
            }
        }
    }

    private var statColumns: [GridItem] {
        dynamicTypeSize.isAccessibilitySize
            ? [GridItem(.flexible())]
            : [GridItem(.flexible(), spacing: VocaMetrics.related), GridItem(.flexible())]
    }

    private func statCard(
        _ symbol: String,
        _ tint: SemanticPalette.StatTint,
        _ value: String,
        _ label: String
    ) -> some View {
        VocaCard {
            VStack(alignment: .leading, spacing: VocaMetrics.related) {
                StatChip(symbol: symbol, tint: tint)
                Text(value)
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(Color.vocaPrimaryText)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                Text(label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.vocaSecondaryText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(label), \(value)")
        }
    }

    private var streakCard: some View {
        let streak = stats.currentStreak(at: now)
        return VocaCard {
            VStack(alignment: .leading, spacing: VocaMetrics.related) {
                StatChip(symbol: "flame.fill", tint: .streak)
                Text(StatsFormat.streak(streak))
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(Color.vocaPrimaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                Text("Best \(StatsFormat.streak(stats.bestStreak))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.vocaSecondaryText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "Current streak, \(StatsFormat.streak(streak)). Best, \(StatsFormat.streak(stats.bestStreak))"
            )
        }
    }

    private var recentActivityCard: some View {
        let days = stats.lastSevenDays(endingAt: now)
        return VStack(alignment: .leading, spacing: VocaMetrics.related) {
            VocaSectionHeader(title: "Last 7 days")
            VocaCard {
                VStack(alignment: .leading, spacing: VocaMetrics.padding) {
                    activityChart(days)
                    if stats.hasAny {
                        Divider()
                        VStack(spacing: VocaMetrics.related + 2) {
                            ForEach(days.reversed().filter { $0.dictations > 0 }) { day in
                                activityRow(day)
                            }
                        }
                    } else {
                        Text("Your daily words and sessions will build a seven-day view here.")
                            .font(.subheadline)
                            .foregroundStyle(Color.vocaSecondaryText)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
            }
        }
    }

    private func activityChart(_ days: [DailyUsage]) -> some View {
        let maximum = max(days.map(\.words).max() ?? 0, 1)
        return HStack(alignment: .bottom, spacing: VocaMetrics.related) {
            ForEach(days) { day in
                VStack(spacing: 7) {
                    Text(day.words == 0 ? "" : StatsFormat.count(day.words))
                        .font(.caption2.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Color.vocaSecondaryText)
                        .frame(height: 16)
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(day.words == 0 ? Color.vocaRecessedSurface : Color.brand)
                        .frame(height: activityBarHeight(day.words, maximum: maximum))
                    Text(StatsFormat.shortDayLabel(day.key))
                        .font(.caption2.weight(day.key == UsageStats.dayKey(now) ? .bold : .regular))
                        .foregroundStyle(
                            day.key == UsageStats.dayKey(now)
                                ? Color.vocaPrimaryText
                                : Color.vocaSecondaryText
                        )
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    "\(StatsFormat.dayLabel(day.key, now: now)), \(StatsFormat.words(day.words)), \(StatsFormat.sessions(day.dictations))"
                )
            }
        }
        .frame(height: 154, alignment: .bottom)
    }

    private func activityRow(_ day: DailyUsage) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(StatsFormat.dayLabel(day.key, now: now))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.vocaPrimaryText)
            Spacer()
            Text(
                "\(StatsFormat.words(day.words)) · \(StatsFormat.sessions(day.dictations)) · "
                    + StatsFormat.duration(day.seconds)
            )
            .font(.caption)
            .foregroundStyle(Color.vocaSecondaryText)
            .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }

    private func activityBarHeight(_ words: Int, maximum: Int) -> CGFloat {
        words == 0 ? 8 : max(16, CGFloat(words) / CGFloat(maximum) * 94)
    }

    private var shareCard: some View {
        VocaCard {
            VStack(alignment: .leading, spacing: VocaMetrics.padding) {
                HStack(alignment: .top, spacing: VocaMetrics.related) {
                    StatChip(symbol: "doc.on.clipboard", tint: .words)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(StatsCopy.shareTitle)
                            .font(.headline)
                            .foregroundStyle(Color.vocaPrimaryText)
                        Text(StatsCopy.shareSubtitle)
                            .font(.caption)
                            .foregroundStyle(Color.vocaSecondaryText)
                    }
                }

                HStack(spacing: VocaMetrics.related) {
                    ShareButton(tint: Color.brand, label: "Copy", accessibilityLabel: "Copy stats card") {
                        Image(systemName: shareState == .copied ? "checkmark" : "doc.on.clipboard")
                    } action: { copyCard() }

                    ShareButton(tint: Color.vocaPrimaryText, label: "X", accessibilityLabel: "Share stats on X") {
                        Text("X").font(.headline.weight(.semibold))
                    } action: { share(to: .x) }

                    ShareButton(tint: Self.linkedInBlue, label: "LinkedIn", accessibilityLabel: "Share stats on LinkedIn") {
                        Text("in")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Self.linkedInBlue, in: RoundedRectangle(cornerRadius: 3))
                    } action: { share(to: .linkedIn) }
                }

                if let note = shareNote {
                    Label(note.text, systemImage: note.symbol)
                        .font(.caption)
                        .foregroundStyle(note.isError ? Color.vocaError : Color.vocaSecondaryText)
                        .transition(.opacity)
                } else {
                    Text(StatsCopy.shareFootnote)
                        .font(.caption2)
                        .foregroundStyle(Color.vocaSecondaryText)
                }
            }
            .animation(.easeInOut(duration: 0.18), value: shareState)
        }
    }

    private static let linkedInBlue = Color(red: 0.04, green: 0.40, blue: 0.71)

    private var privacyNote: some View {
        Label(
            "Counts stay on this iPhone. Nothing is posted until you finish the post yourself.",
            systemImage: "lock.fill"
        )
        .font(.footnote)
        .foregroundStyle(Color.vocaSecondaryText)
        .padding(.horizontal, VocaMetrics.tight)
    }

    private var shareNote: (text: String, symbol: String, isError: Bool)? {
        switch shareState {
        case .idle:
            nil
        case .copied:
            ("Card copied. Paste it wherever you’d like.", "checkmark.circle.fill", false)
        case .opened(let destination):
            ("Card copied. Paste it into your \(destination.label) post.", "doc.on.clipboard", false)
        case .failed(let message):
            (message, "exclamationmark.triangle.fill", true)
        }
    }

    private func copyCard() {
        guard StatsShareExporter.copyCard(stats, now: now) else {
            setShareState(.failed("Couldn’t copy the card image."), clearingAfter: 6)
            return
        }
        setShareState(.copied, clearingAfter: 3)
    }

    private func share(to destination: StatsShareDestination) {
        guard StatsShareExporter.copyCard(stats, now: now) else {
            setShareState(.failed("Couldn’t copy the card image."), clearingAfter: 6)
            return
        }
        let message = StatsShareComposer.message(stats, now: now)
        guard let url = StatsShareComposer.composerURL(destination, message: message) else {
            setShareState(.failed("Couldn’t prepare the \(destination.label) post."), clearingAfter: 6)
            return
        }
        openURL(url) { accepted in
            Task { @MainActor in
                if accepted {
                    setShareState(.opened(destination), clearingAfter: 5)
                } else {
                    setShareState(.failed("Couldn’t open \(destination.label)."), clearingAfter: 6)
                }
            }
        }
    }

    private func setShareState(_ state: ShareState, clearingAfter seconds: Double) {
        shareStateToken += 1
        let token = shareStateToken
        shareState = state
        Task {
            try? await Task.sleep(for: .seconds(seconds))
            if shareStateToken == token { shareState = .idle }
        }
    }

    private func refresh(folding: Bool) {
        now = Date()
        if folding, let folded = try? store.fold() {
            stats = folded
        } else {
            stats = store.current()
        }
    }

    private func reset() {
        do {
            try store.reset()
            refresh(folding: false)
        } catch {
            setShareState(.failed("Couldn’t reset statistics."), clearingAfter: 6)
        }
    }
}

struct StatChip: View {
    let symbol: String
    let tint: SemanticPalette.StatTint
    var size: CGFloat = 30

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .medium))
            .foregroundStyle(Color.vocaStatSymbol(tint))
            .frame(width: size, height: size)
            .background(
                Color.vocaStatChip(tint),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .accessibilityHidden(true)
    }
}

struct ShareButton<Mark: View>: View {
    let tint: Color
    let label: String
    let accessibilityLabel: String
    @ViewBuilder var mark: Mark
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: VocaMetrics.tight) {
                mark
                Text(label)
                    .font(.caption.weight(.medium))
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: VocaMetrics.minimumTarget + 12)
            .foregroundStyle(tint)
            .background(
                tint.opacity(0.08),
                in: RoundedRectangle(cornerRadius: VocaMetrics.fieldRadius, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: VocaMetrics.fieldRadius, style: .continuous)
                    .strokeBorder(tint.opacity(0.25), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}
