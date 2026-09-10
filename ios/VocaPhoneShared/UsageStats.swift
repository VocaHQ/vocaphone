import Foundation

struct UsageStats: Codable, Equatable, Sendable {
    var totalWords: Int = 0
    var totalDictations: Int = 0
    var totalSeconds: Double = 0
    var lastDayKey: String = ""
    var currentStreak: Int = 0
    var bestStreak: Int = 0
    var dailyWords: [String: Int] = [:]

    static let dailyLimit = 7

    var hasAny: Bool { totalDictations > 0 }

    var averageWordsPerMinute: Double {
        guard totalSeconds > 0 else { return 0 }
        return Double(totalWords) / (totalSeconds / 60)
    }

    func currentStreak(at now: Date, timeZone: TimeZone = .current) -> Int {
        guard !lastDayKey.isEmpty else { return 0 }
        guard let elapsed = Self.daysBetween(lastDayKey, Self.dayKey(now, timeZone: timeZone)) else {
            return 0
        }
        if Self.isUnreconcilableFuture(elapsed) { return 0 }
        return elapsed <= 1 ? currentStreak : 0
    }

    func recording(dayKey key: String, words: Int, seconds: Double) -> UsageStats {
        guard words > 0 else { return self }
        var next = self
        next.totalWords += words
        next.totalDictations += 1
        next.totalSeconds += max(seconds, 0)
        var daily = dailyWords
        daily[key, default: 0] += words
        next.dailyWords = Self.pruneDaily(daily)
        let streak = Self.advanceStreak(currentStreak, lastDayKey: lastDayKey, todayKey: key)
        next.currentStreak = streak
        next.bestStreak = max(bestStreak, streak)
        next.lastDayKey = Self.nextDayKey(lastDayKey, todayKey: key)
        return next
    }

    private static func gregorian(_ timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    static func dayKey(_ date: Date, timeZone: TimeZone = .current) -> String {
        let parts = gregorian(timeZone).dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            parts.year ?? 0,
            parts.month ?? 0,
            parts.day ?? 0
        )
    }

    static func daysBetween(_ fromKey: String, _ toKey: String) -> Int? {
        guard let from = epochDay(fromKey), let to = epochDay(toKey) else { return nil }
        return to - from
    }

    static func isDayKey(_ value: String) -> Bool { epochDay(value) != nil }

    private static func epochDay(_ key: String) -> Int? {
        let parts = key.split(separator: "-", omittingEmptySubsequences: false)
        guard key.count == 10, parts.count == 3,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              parts[0].count == 4, parts[1].count == 2, parts[2].count == 2
        else { return nil }
        let calendar = gregorian(TimeZone(secondsFromGMT: 0) ?? .current)
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        guard let date = calendar.date(from: components) else { return nil }
        let back = calendar.dateComponents([.year, .month, .day], from: date)
        guard back.year == year, back.month == month, back.day == day else { return nil }
        return Int((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    static func advanceStreak(_ current: Int, lastDayKey: String, todayKey: String) -> Int {
        guard !lastDayKey.isEmpty else { return 1 }
        guard let elapsed = daysBetween(lastDayKey, todayKey) else { return 1 }
        if isUnreconcilableFuture(elapsed) { return 1 }
        if elapsed <= 0 { return max(current, 1) }
        if elapsed == 1 { return max(current, 0) + 1 }
        return 1
    }

    static func nextDayKey(_ stored: String, todayKey: String) -> String {
        guard isDayKey(stored) else { return todayKey }
        if let elapsed = daysBetween(stored, todayKey), isUnreconcilableFuture(elapsed) {
            return todayKey
        }
        return max(stored, todayKey)
    }

    private static func isUnreconcilableFuture(_ elapsed: Int) -> Bool {
        elapsed < -dailyLimit
    }

    static func pruneDaily(_ daily: [String: Int]) -> [String: Int] {
        guard daily.count > dailyLimit else { return daily }
        let ranked = daily.keys.sorted { left, right in
            let leftReadable = isDayKey(left)
            let rightReadable = isDayKey(right)
            if leftReadable != rightReadable { return leftReadable }
            return left > right
        }
        return Dictionary(uniqueKeysWithValues: ranked.prefix(dailyLimit).map { ($0, daily[$0] ?? 0) })
    }

    func recentDays(limit: Int = dailyLimit) -> [(key: String, words: Int)] {
        dailyWords
            .filter { Self.isDayKey($0.key) }
            .sorted { $0.key > $1.key }
            .prefix(limit)
            .map { (key: $0.key, words: $0.value) }
    }

    static func wordCount(_ text: String) -> Int {
        var count = 0
        text.enumerateSubstrings(
            in: text.startIndex..<text.endIndex,
            options: [.byWords, .localized]
        ) { _, _, _, _ in
            count += 1
        }
        return count
    }

    static func nextDayStart(after date: Date, timeZone: TimeZone = .current) -> Date {
        let calendar = gregorian(timeZone)
        let today = calendar.startOfDay(for: date)
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: today) else {
            return date.addingTimeInterval(86_400)
        }
        return calendar.startOfDay(for: tomorrow)
    }
}
