import SwiftUI
import UIKit

enum StatsShareDestination: CaseIterable {
    case x
    case linkedIn

    var label: String {
        switch self {
        case .x: "X"
        case .linkedIn: "LinkedIn"
        }
    }
}

enum StatsShareComposer {
    static let site = "https://vocaphone.vocahq.com"

    static func message(_ stats: UsageStats, now: Date) -> String {
        var lines = "🎤 I've dictated \(StatsFormat.count(stats.totalWords)) "
        lines += stats.totalWords == 1 ? "word" : "words"
        lines += " with VocaPhone.\n\n"
        lines += "📊 \(StatsFormat.count(stats.totalDictations)) "
        lines += stats.totalDictations == 1 ? "dictation" : "dictations"
        if stats.averageWordsPerMinute > 0 {
            lines += " · ⚡️ \(Int(stats.averageWordsPerMinute.rounded())) WPM"
        }
        let streak = stats.currentStreak(at: now)
        if streak > 0 {
            lines += " · 🔥 \(streak)-day streak"
        }
        lines += "\n\nRuns on my phone, privately. 🔒\n\(site)"
        return lines
    }

    static func composerURL(_ destination: StatsShareDestination, message: String) -> URL? {
        let encoded = message.addingPercentEncoding(
            withAllowedCharacters: .alphanumerics
        ) ?? ""
        switch destination {
        case .x:
            return URL(string: "https://x.com/intent/post?text=\(encoded)")
        case .linkedIn:
            return URL(string: "https://www.linkedin.com/feed/?shareActive=true&text=\(encoded)")
        }
    }
}

struct StatsShareCard: View {
    let stats: UsageStats
    let now: Date

    static let size = CGSize(width: 1_080, height: 720)

    var body: some View {
        VStack(alignment: .leading, spacing: 36) {
            Text("VOCAPHONE")
                .font(.system(size: 30, weight: .semibold, design: .rounded))
                .tracking(6)
                .foregroundStyle(Color.brand)

            Text(StatsFormat.count(stats.totalWords))
                .font(.system(size: 190, weight: .bold, design: .rounded))
                .foregroundStyle(Color.vocaPrimaryText)

            Text(stats.totalWords == 1 ? "word dictated" : "words dictated")
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(Color.vocaSecondaryText)

            Spacer(minLength: 0)

            HStack(spacing: 56) {
                cardStat(StatsFormat.count(stats.totalDictations), "dictations")
                cardStat(StatsFormat.wordsPerMinute(stats.averageWordsPerMinute), "WPM")
                cardStat(StatsFormat.count(stats.currentStreak(at: now)), "day streak")
            }
        }
        .padding(72)
        .frame(width: Self.size.width, height: Self.size.height, alignment: .leading)
        .background(Color.vocaCanvas)
    }

    private func cardStat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(value)
                .font(.system(size: 58, weight: .semibold, design: .rounded))
                .foregroundStyle(Color.vocaPrimaryText)
            Text(label)
                .font(.system(size: 30))
                .foregroundStyle(Color.vocaSecondaryText)
        }
    }
}

@MainActor
enum StatsShareExporter {
    
    static func renderCard(_ stats: UsageStats, now: Date, style: UIUserInterfaceStyle) -> UIImage? {
        let renderer = ImageRenderer(content: StatsShareCard(stats: stats, now: now))
        renderer.scale = 1
        renderer.proposedSize = ProposedViewSize(StatsShareCard.size)
        
        var image: UIImage?
        UITraitCollection(userInterfaceStyle: style).performAsCurrent {
            image = renderer.uiImage
        }
        return image
    }

    @discardableResult
    static func copyCard(_ stats: UsageStats, now: Date, style: UIUserInterfaceStyle) -> Bool {
        guard let image = renderCard(stats, now: now, style: style) else { return false }
        UIPasteboard.general.image = image
        return true
    }
}
