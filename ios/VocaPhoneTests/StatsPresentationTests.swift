import Foundation
import Testing

struct StatsPresentationTests {
    private let now = Date(timeIntervalSince1970: 1_778_457_600)

    private var stats: UsageStats {
        UsageStats(
            totalWords: 12_500,
            totalDictations: 42,
            totalSeconds: 3_600,
            lastDayKey: UsageStats.dayKey(now),
            currentStreak: 4,
            bestStreak: 9
        )
    }

    @Test func shareCopyNamesBothPrivateProcessingRoutes() {
        let message = StatsShareComposer.message(stats, now: now)
        #expect(message.contains("on my iPhone or through my own gateway"))
        #expect(!message.contains("Runs on my phone"))
        #expect(message.contains(StatsShareComposer.site))
    }

    @Test(arguments: StatsShareDestination.allCases)
    func composerURLsRoundTripTheEntireMessage(_ destination: StatsShareDestination) throws {
        let message = "words & sessions + streak; हिन्दी 🔒"
        let url = try #require(StatsShareComposer.composerURL(destination, message: message))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let items = try #require(components.queryItems)
        #expect(items.first(where: { $0.name == "text" })?.value == message)
    }

    @Test func linkedinUsesTheFeedComposerContract() throws {
        let url = try #require(StatsShareComposer.composerURL(.linkedIn, message: "hello"))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.host == "www.linkedin.com")
        #expect(components.path == "/feed/")
        #expect(components.queryItems?.contains(URLQueryItem(name: "shareActive", value: "true")) == true)
    }

    @Test func singularPublicCopyIsGrammatical() {
        let one = UsageStats(
            totalWords: 1,
            totalDictations: 1,
            totalSeconds: 1,
            lastDayKey: UsageStats.dayKey(now),
            currentStreak: 1,
            bestStreak: 1
        )
        let message = StatsShareComposer.message(one, now: now)
        #expect(message.contains("1 word"))
        #expect(message.contains("1 session"))
        #expect(!message.contains("1 sessions"))
    }
}
