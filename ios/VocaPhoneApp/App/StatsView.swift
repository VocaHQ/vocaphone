import SwiftUI

enum StatsCopy {
    static let empty = "Your dictation totals will appear here after your first dictation."
    static let resetTitle = "Reset statistics?"
    static let resetBody =
        "This permanently deletes your usage totals. Your transcripts are not affected."
    static let resetConfirm = "Reset"
    static let speedCaption = "Speaking Speed"
    static let shareTitle = "Share your progress"
    static let shareSubtitle = "A polished card, ready to post"
    static let shareFootnote = "The card is copied to your clipboard — paste it into your post."
    static let shareCopied = "Card copied"

    static func menuDetail(_ stats: UsageStats, now: Date) -> String {
        guard stats.hasAny else { return "Words, speaking speed and streaks" }
        let streak = stats.currentStreak(at: now)
        return "\(StatsFormat.count(stats.totalWords)) words · \(StatsFormat.streak(streak)) streak"
    }
}

enum StatsFormat {
    static func count(_ value: Int) -> String {
        value.formatted(.number.grouping(.automatic))
    }

    static func duration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        if total < 60 { return "\(total)s" }
        if total < 3_600 { return "\(total / 60)m" }
        let hours = total / 3_600
        let minutes = (total % 3_600) / 60
        return minutes == 0 ? "\(hours)h" : "\(hours)h \(minutes)m"
    }

    static func wordsPerMinute(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    static func streak(_ days: Int) -> String {
        "\(days) \(days == 1 ? "day" : "days")"
    }

    static func words(_ count: Int) -> String {
        "\(Self.count(count)) \(count == 1 ? "word" : "words")"
    }

    static func dayLabel(_ key: String, now: Date, timeZone: TimeZone = .current) -> String {
        let today = UsageStats.dayKey(now, timeZone: timeZone)
        guard let elapsed = UsageStats.daysBetween(key, today) else { return key }
        switch elapsed {
        case 0: return "Today"
        case 1: return "Yesterday"
        default: return key
        }
    }
}

struct StatsView: View {
    @State private var stats = UsageStats()
    @State private var now = Date()
    @State private var confirmingReset = false
    @State private var copiedCard = false
    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var colorScheme

    private let store = UsageStatsStore.shared

    var body: some View {
        ScrollView {
            if stats.hasAny {
                content
            } else {
                Text(StatsCopy.empty)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(VocaMetrics.padding)
            }
        }
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

    private var content: some View {
        VStack(alignment: .leading, spacing: VocaMetrics.padding) {
            speedCard
            statGrid
            recentActivityCard
            shareCard

            VocaDestructiveButton(title: "Reset statistics") { confirmingReset = true }
                .frame(maxWidth: .infinity)
                .padding(.top, VocaMetrics.related)
        }
        .padding(VocaMetrics.padding)
    }


    private var speedCard: some View {
        VocaCard {
            VStack(alignment: .leading, spacing: VocaMetrics.related) {
                StatChipLabel(symbol: "chart.line.uptrend.xyaxis", tint: .speed, title: "Speed")
                HStack(alignment: .firstTextBaseline, spacing: VocaMetrics.tight) {
                    Text(StatsFormat.wordsPerMinute(stats.averageWordsPerMinute))
                        .font(.system(.largeTitle, design: .rounded).weight(.bold))
                        .foregroundStyle(Color.vocaPrimaryText)
                    Text("WPM")
                        .font(.subheadline)
                        .foregroundStyle(Color.vocaSecondaryText)
                }
                Text(StatsCopy.speedCaption)
                    .font(.caption)
                    .foregroundStyle(Color.vocaSecondaryText)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "Speaking speed, \(StatsFormat.wordsPerMinute(stats.averageWordsPerMinute)) words per minute"
            )
        }
    }

    private var statGrid: some View {
        Grid(horizontalSpacing: VocaMetrics.padding, verticalSpacing: VocaMetrics.padding) {
            GridRow {
                statCard("text.alignleft", .words, StatsFormat.count(stats.totalWords), "Words")
                statCard("mic.fill", .dictations, StatsFormat.count(stats.totalDictations), "Dictations")
            }
            GridRow {
                statCard("clock", .time, StatsFormat.duration(stats.totalSeconds), "Time")
                streakCard
            }
        }
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
                    .font(.system(.title, design: .rounded).weight(.bold))
                    .foregroundStyle(Color.vocaPrimaryText)
                Text(label)
                    .font(.caption)
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
                HStack(alignment: .firstTextBaseline, spacing: VocaMetrics.tight) {
                    Text(StatsFormat.count(streak))
                        .font(.system(.title, design: .rounded).weight(.bold))
                        .foregroundStyle(Color.vocaPrimaryText)
                    Text(streak == 1 ? "day" : "days")
                        .font(.subheadline)
                        .foregroundStyle(Color.vocaSecondaryText)
                }
                Text("Streak · Best \(StatsFormat.streak(stats.bestStreak))")
                    .font(.caption)
                    .foregroundStyle(Color.vocaSecondaryText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "Streak, \(StatsFormat.streak(streak)). Best \(StatsFormat.streak(stats.bestStreak))"
            )
        }
    }

    private var recentActivityCard: some View {
        let days = stats.recentDays()
        return Group {
            if days.isEmpty {
                EmptyView()
            } else {
                VocaCard {
                    VStack(alignment: .leading, spacing: VocaMetrics.related) {
                        StatChipLabel(
                            symbol: "chart.line.uptrend.xyaxis",
                            tint: .speed,
                            title: "Recent activity"
                        )
                        ForEach(days, id: \.key) { day in
                            LabeledContent {
                                Text(StatsFormat.words(day.words))
                                    .font(.subheadline)
                                    .foregroundStyle(Color.vocaSecondaryText)
                            } label: {
                                Text(StatsFormat.dayLabel(day.key, now: now))
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(Color.vocaPrimaryText)
                            }
                        }
                    }
                }
            }
        }
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
                    ShareButton(
                        tint: Color.brand,
                        label: copiedCard ? StatsCopy.shareCopied : "Copy"
                    ) {
                        Image(systemName: copiedCard ? "checkmark" : "doc.on.clipboard")
                    } action: {
                        copyCard()
                    }

                    ShareButton(tint: Color.vocaPrimaryText, label: "X") {
                        Text("\u{1D54F}").font(.title3)
                    } action: {
                        share(to: .x)
                    }

                    ShareButton(tint: Self.linkedInBlue, label: "LinkedIn") {
                        Text("in")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Self.linkedInBlue, in: RoundedRectangle(cornerRadius: 3))
                    } action: {
                        share(to: .linkedIn)
                    }
                }

                Text(StatsCopy.shareFootnote)
                    .font(.caption2)
                    .foregroundStyle(Color.vocaSecondaryText)
            }
        }
    }

    private static let linkedInBlue = Color(red: 0.04, green: 0.40, blue: 0.71)

    private func copyCard() {
        StatsShareExporter.copyCard(stats, now: now, style: interfaceStyle)
        withAnimation { copiedCard = true }
    }

    private func share(to destination: StatsShareDestination) {
        StatsShareExporter.copyCard(stats, now: now, style: interfaceStyle)
        let message = StatsShareComposer.message(stats, now: now)
        guard let url = StatsShareComposer.composerURL(destination, message: message) else { return }
        openURL(url)
    }

    private var interfaceStyle: UIUserInterfaceStyle {
        colorScheme == .dark ? .dark : .light
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
        try? store.reset()
        refresh(folding: false)
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

struct StatChipLabel: View {
    let symbol: String
    let tint: SemanticPalette.StatTint
    let title: String

    var body: some View {
        HStack(spacing: VocaMetrics.related) {
            StatChip(symbol: symbol, tint: tint, size: 26)
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.vocaSecondaryText)
        }
    }
}

struct ShareButton<Mark: View>: View {
    let tint: Color
    let label: String
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
        .accessibilityLabel("Share to \(label)")
    }
}
