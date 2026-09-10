import Foundation

enum StatsCopy {
    static let resetTitle = "Reset statistics?"
    static let resetBody =
        "This permanently deletes your usage totals. Your transcripts are not affected."
    static let resetConfirm = "Reset"
    static let speedCaption = "Average speaking speed"
    static let shareTitle = "Share your progress"
    static let shareSubtitle = "A private summary you choose where to post"
    static let shareFootnote = "Social composers cannot attach images automatically. The card is copied so you can paste it into your post."

    static func menuDetail(_ stats: UsageStats, now: Date) -> String {
        guard stats.hasAny else { return "Words, speaking speed and streaks" }
        return "\(StatsFormat.count(stats.totalWords)) words · "
            + "\(StatsFormat.streak(stats.currentStreak(at: now))) streak"
    }
}

enum StatsFormat {
    static func count(_ value: Int) -> String {
        value.formatted(.number.grouping(.automatic))
    }

    static func duration(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded()))
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

    static func sessions(_ count: Int) -> String {
        "\(Self.count(count)) \(count == 1 ? "session" : "sessions")"
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

    static func shortDayLabel(_ key: String) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: key) else { return "–" }
        formatter.dateFormat = "EEEEE"
        return formatter.string(from: date)
    }
}

enum StatsShareDestination: CaseIterable, Equatable, Sendable {
    case x
    case linkedIn

    var label: String { self == .x ? "X" : "LinkedIn" }
}

enum StatsShareComposer {
    static let site = "https://vocaphone.vocahq.com"

    static func message(_ stats: UsageStats, now: Date) -> String {
        var details = "📊 \(StatsFormat.sessions(stats.totalDictations))"
        if stats.averageWordsPerMinute > 0 {
            details += " · ⚡️ \(Int(stats.averageWordsPerMinute.rounded())) WPM"
        }
        let streak = stats.currentStreak(at: now)
        if streak > 0 { details += " · 🔥 \(streak)-day streak" }
        return [
            "🎤 I've dictated \(StatsFormat.words(stats.totalWords)) with VocaPhone.",
            details,
            "Private voice typing — on my iPhone or through my own gateway. 🔒",
            site,
        ].joined(separator: "\n\n")
    }

    static func composerURL(_ destination: StatsShareDestination, message: String) -> URL? {
        let base = destination == .x
            ? "https://x.com/intent/post"
            : "https://www.linkedin.com/feed/"
        guard var components = URLComponents(string: base) else { return nil }
        components.queryItems = destination == .x
            ? [URLQueryItem(name: "text", value: message)]
            : [
                URLQueryItem(name: "shareActive", value: "true"),
                URLQueryItem(name: "text", value: message),
            ]
        return components.url
    }
}
