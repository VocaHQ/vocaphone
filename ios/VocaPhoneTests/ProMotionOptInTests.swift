import Foundation
import Testing

/// ProMotion is opt-in, per bundle, and silent when it is missing.
///
/// `CADisableMinimumFrameDurationOnPhone` is what lets Core Animation and
/// `CADisplayLink` run above 60Hz on an iPhone. Without it iOS clamps the whole
/// process and returns no error, so `SwipeTrailView` asking for
/// `CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)` was answered at
/// 60 on a 120Hz panel and nothing said so.
///
/// The keyboard runs in its own process, so its own `Info.plist` has to carry
/// the key — inheriting the containing app's would not have helped it. That is
/// the part easiest to drop, which is why it is pinned here rather than left to
/// be noticed on a device.
struct ProMotionOptInTests {
    @Test(arguments: ["VocaPhoneApp", "VocaPhoneKeyboard"])
    func everyBundleThatDrawsOptsIntoTheFullFrameRate(target: String) throws {
        let plist = Self.iosDirectory
            .appendingPathComponent(target)
            .appendingPathComponent("Info.plist")
        let contents = try Data(contentsOf: plist)
        let parsed = try PropertyListSerialization.propertyList(
            from: contents,
            format: nil
        ) as? [String: Any]

        #expect(parsed?["CADisableMinimumFrameDurationOnPhone"] as? Bool == true)
    }

    private static var iosDirectory: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

/// Pins the App Store Review answers that are easy to undo in a later edit:
/// no `UIBackgroundModes` audio, no usage string that claims the microphone
/// stays ready in the background, and no custom "Allow" button in front of
/// the system microphone prompt.
struct AppStoreReviewMicrophoneTests {
    @Test(arguments: ["VocaPhoneApp", "VocaPhoneKeyboard", "VocaPhoneLiveActivity"])
    func noBundleDeclaresBackgroundAudio(target: String) throws {
        let parsed = try Self.plist(named: target)
        #expect(!parsed.keys.contains("UIBackgroundModes"))
    }

    @Test func microphoneUsageDoesNotClaimBackgroundReadiness() throws {
        let parsed = try Self.plist(named: "VocaPhoneApp")
        let usage = try #require(parsed["NSMicrophoneUsageDescription"] as? String)
        #expect(!usage.isEmpty)
        #expect(!usage.localizedCaseInsensitiveContains("keep the microphone ready"))
        #expect(!usage.localizedCaseInsensitiveContains("between dictations"))
        #expect(!usage.localizedCaseInsensitiveContains("background"))
    }

    @Test func firstRunAndSettingsWireThePrePromptToContinue() throws {
        let setup = try Self.source("VocaPhoneApp/App/SetupView.swift")
        let settings = try Self.source("VocaPhoneApp/App/SettingsView.swift")
        #expect(setup.contains("onboardingActionTitle"))
        #expect(!setup.contains("\"Allow access\""))
        #expect(!setup.contains("\"Allow microphone access\""))
        #expect(settings.contains("MicrophoneAccess.prePromptActionTitle"))
        #expect(!settings.contains("\"Allow microphone access\""))
        #expect(!settings.contains("\"Allow access\""))
    }

    @Test func quickDictationCopyDoesNotPromiseBackgroundInput() {
        for duration in QuickDictationDuration.allCases {
            #expect(
                !duration.settingsFooter
                    .localizedCaseInsensitiveContains("background input")
            )
            #expect(
                !duration.settingsFooter
                    .localizedCaseInsensitiveContains("background-audio")
            )
        }
    }

    private static func plist(named target: String) throws -> [String: Any] {
        let url = iosDirectory
            .appendingPathComponent(target)
            .appendingPathComponent("Info.plist")
        return try #require(
            PropertyListSerialization.propertyList(
                from: Data(contentsOf: url),
                format: nil
            ) as? [String: Any]
        )
    }

    private static func source(_ path: String) throws -> String {
        try String(
            contentsOf: iosDirectory.appendingPathComponent(path),
            encoding: .utf8
        )
    }

    private static var iosDirectory: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
