import Testing
import UIKit

/// The standard keyboard click and optional custom haptics have different
/// owners. A click follows iOS Keyboard Clicks; a custom haptic needs both the
/// explicit VocaPhone preference and Full Access.
@MainActor
struct KeyboardHapticsTests {
    @Test func hapticsNeedBothThePreferenceAndFullAccess() {
        #expect(KeyboardHaptics.allowsHaptics(preferenceEnabled: true, hasFullAccess: true))
        #expect(!KeyboardHaptics.allowsHaptics(preferenceEnabled: false, hasFullAccess: true))
        // The setting is on and the platform says no. This is the case the
        // Keyboard settings screen explains rather than leaving silent.
        #expect(!KeyboardHaptics.allowsHaptics(preferenceEnabled: true, hasFullAccess: false))
        #expect(!KeyboardHaptics.allowsHaptics(preferenceEnabled: false, hasFullAccess: false))
    }

    @Test func committedTextAlwaysUsesTheSystemClick() {
        #expect(
            KeyboardFeedbackPolicy.events(
                for: .committedText,
                typingHapticsEnabled: false,
                hasFullAccess: false
            ) == [.inputClick]
        )
        #expect(
            KeyboardFeedbackPolicy.events(
                for: .committedText,
                typingHapticsEnabled: false,
                hasFullAccess: true
            ) == [.inputClick]
        )
        #expect(
            KeyboardFeedbackPolicy.events(
                for: .committedText,
                typingHapticsEnabled: true,
                hasFullAccess: true
            ) == [.inputClick, .typingHaptic]
        )
    }

    @Test func immediateKeyboardControlsClickWithoutAddingATypingImpact() {
        for preferenceEnabled in [false, true] {
            for hasFullAccess in [false, true] {
                #expect(
                    KeyboardFeedbackPolicy.events(
                        for: .committedKeyAction,
                        typingHapticsEnabled: preferenceEnabled,
                        hasFullAccess: hasFullAccess
                    ) == [.inputClick]
                )
            }
        }
    }

    @Test func optionalHapticsAreSilentWithoutBothPermissions() {
        for interaction in [
            KeyboardFeedbackInteraction.selectionChanged,
            .dictationAction,
            .swipeBegan,
            .swipeCommitted,
        ] {
            #expect(
                KeyboardFeedbackPolicy.events(
                    for: interaction,
                    typingHapticsEnabled: false,
                    hasFullAccess: true
                ).isEmpty
            )
            #expect(
                KeyboardFeedbackPolicy.events(
                    for: interaction,
                    typingHapticsEnabled: true,
                    hasFullAccess: false
                ).isEmpty
            )
        }
    }

    /// Full Access can be revoked in Settings while an extension instance lives
    /// on, so the answer is re-asked on every appearance rather than cached at
    /// load.
    @Test func attachingUpdatesTheAccessTheKeyboardWasGiven() {
        let view = UIView()
        let haptics = KeyboardHaptics.shared
        haptics.attach(to: view, hasFullAccess: true)
        #expect(haptics.hasFullAccess)
        haptics.attach(to: view, hasFullAccess: false)
        #expect(!haptics.hasFullAccess)
        haptics.release()
    }

    /// Every event has to be safe to call with no engine behind it: the tests,
    /// the simulator and a keyboard without Full Access all run this path.
    @Test func everyEventIsSafeWithoutAnEngine() {
        let haptics = KeyboardHaptics.shared
        haptics.attach(to: UIView(), hasFullAccess: false)
        haptics.textCommitted()
        haptics.keyActionCommitted()
        haptics.selectionChanged()
        haptics.action()
        haptics.swipeBegan()
        haptics.swipeCommitted()
        haptics.warmUp()
        haptics.release()
    }
}
