import UIKit

/// The semantic feedback a keyboard interaction may produce. Keeping this as
/// data lets tests prove that a cancelled touch cannot buzz or click, without
/// pretending the simulator has a Taptic Engine.
enum KeyboardFeedbackEvent: Equatable {
    case inputClick
    case typingHaptic
    case selectionHaptic
    case actionHaptic
    case swipeBeganHaptic
    case swipeCommittedHaptic
}

enum KeyboardFeedbackInteraction {
    case committedText
    case committedKeyAction
    case selectionChanged
    case dictationAction
    case swipeBegan
    case swipeCommitted
}

enum KeyboardFeedbackPolicy {
    static func events(
        for interaction: KeyboardFeedbackInteraction,
        typingHapticsEnabled: Bool,
        hasFullAccess: Bool
    ) -> [KeyboardFeedbackEvent] {
        let allowsHaptics = typingHapticsEnabled && hasFullAccess
        switch interaction {
        case .committedText:
            return [.inputClick] + (allowsHaptics ? [.typingHaptic] : [])
        case .committedKeyAction:
            // Shift, Delete, plane switching, and the globe act immediately;
            // their visible state change is feedback enough. They still get the
            // standard click so the custom keyboard keeps the iOS convention.
            return [.inputClick]
        case .selectionChanged:
            return allowsHaptics ? [.selectionHaptic] : []
        case .dictationAction:
            return allowsHaptics ? [.actionHaptic] : []
        case .swipeBegan:
            return allowsHaptics ? [.swipeBeganHaptic] : []
        case .swipeCommitted:
            return allowsHaptics ? [.swipeCommittedHaptic] : []
        }
    }
}

/// Every buzz and click the keyboard makes, in one place.
///
/// A standard keyboard click is audio feedback controlled by iOS Settings. It
/// is not a haptic and must not be disabled by VocaPhone's optional tactile
/// preference. Custom haptics are deliberately opt-in, occur only after an
/// interaction has committed, and use restrained intensities because typing is
/// the keyboard's highest-frequency action.
@MainActor
final class KeyboardHaptics {
    static let shared = KeyboardHaptics()

    /// Whether the extension can reach the Taptic Engine at all. Only the
    /// controller knows, so it sets this on load and on every appearance —
    /// Full Access can be revoked from Settings while the instance lives on.
    var hasFullAccess = false

    /// Split out so the rule can be checked without a Taptic Engine. Both halves
    /// matter: the preference is the user's answer and Full Access is the
    /// platform's, and neither substitutes for the other.
    static func allowsHaptics(preferenceEnabled: Bool, hasFullAccess: Bool) -> Bool {
        preferenceEnabled && hasFullAccess
    }

    /// The keyboard's own view. Generators created against a view resolve the
    /// right display, which an extension cannot be assumed to infer on its own.
    private weak var host: UIView?
    private var keyTapGenerator: UIImpactFeedbackGenerator?
    private var actionGenerator: UIImpactFeedbackGenerator?
    private var selectionGenerator: UISelectionFeedbackGenerator?

    private init() {}

    private var allowsHaptics: Bool {
        Self.allowsHaptics(
            preferenceEnabled: KeyboardPreferences.typingHapticsEnabled,
            hasFullAccess: hasFullAccess
        )
    }

    // MARK: - Lifecycle

    /// Binds the generators to the keyboard's view and warms them.
    ///
    /// Called on load and on each appearance, because iOS reuses extension
    /// instances across host apps: the view survives, but the engine does not
    /// stay awake between two visits to the keyboard.
    func attach(to view: UIView, hasFullAccess: Bool) {
        self.hasFullAccess = hasFullAccess
        if host !== view {
            host = view
            // Generators hold on to the view they were made for, so a new host
            // means new generators rather than stale ones aimed at a dead view.
            keyTapGenerator = nil
            actionGenerator = nil
            selectionGenerator = nil
        }
        warmUp()
    }

    /// Wakes the engine so the *next* event lands immediately. Cheap, and a
    /// no-op when the keyboard cannot use haptics anyway.
    func warmUp() {
        guard allowsHaptics else { return }
        impact(&keyTapGenerator, style: .light).prepare()
        selection().prepare()
    }

    /// Lets the engine go back to sleep. Called when the keyboard leaves the
    /// screen: a prepared generator keeps the Taptic Engine powered for a couple
    /// of seconds, which is not something a dismissed keyboard should pay for.
    func release() {
        keyTapGenerator = nil
        actionGenerator = nil
        selectionGenerator = nil
    }

    // MARK: - Events

    /// A character, space, Return, or emoji that has actually inserted. Calling
    /// this only after target resolution prevents a missed or cancelled touch
    /// from confirming the wrong key in the person's hand.
    func textCommitted() {
        perform(.committedText)
    }

    /// An immediate keyboard control such as Shift, Delete, plane switching, or
    /// the globe. These controls retain standard input-click feedback without
    /// adding a custom impact to every tap.
    func keyActionCommitted() {
        perform(.committedKeyAction)
    }

    /// Moving between accent options, entering cursor control, opening the emoji
    /// panel: the user is choosing rather than committing.
    func selectionChanged() {
        perform(.selectionChanged)
    }

    /// A bar button — start, insert, cancel. Weightier than a key because it is
    /// rare and consequential.
    func action() {
        perform(.dictationAction)
    }

    /// The moment a slide becomes a swipe. Light, because the gesture is still
    /// in flight — this says "I am tracing now", not "I typed something".
    func swipeBegan() {
        perform(.swipeBegan)
    }

    /// A traced word has been committed. Firmer than the start: a whole word
    /// just landed, and the user's eyes are on the strip rather than the keys.
    func swipeCommitted() {
        perform(.swipeCommitted)
    }

    private func perform(_ interaction: KeyboardFeedbackInteraction) {
        for event in KeyboardFeedbackPolicy.events(
            for: interaction,
            typingHapticsEnabled: KeyboardPreferences.typingHapticsEnabled,
            hasFullAccess: hasFullAccess
        ) {
            switch event {
            case .inputClick:
                UIDevice.current.playInputClick()
            case .typingHaptic:
                let generator = impact(&keyTapGenerator, style: .light)
                generator.impactOccurred(intensity: 0.35)
                generator.prepare()
            case .selectionHaptic:
                let generator = selection()
                generator.selectionChanged()
                generator.prepare()
            case .actionHaptic:
                let generator = impact(&actionGenerator, style: .soft)
                generator.impactOccurred()
                generator.prepare()
            case .swipeBeganHaptic:
                let generator = impact(&keyTapGenerator, style: .light)
                generator.impactOccurred(intensity: 0.35)
                generator.prepare()
            case .swipeCommittedHaptic:
                let generator = impact(&actionGenerator, style: .soft)
                generator.impactOccurred(intensity: 0.5)
                generator.prepare()
            }
        }
    }

    // MARK: - Generators

    /// The stored generator for a role, made on first use and kept afterwards.
    private func impact(
        _ generator: inout UIImpactFeedbackGenerator?,
        style: UIImpactFeedbackGenerator.FeedbackStyle
    ) -> UIImpactFeedbackGenerator {
        if let generator { return generator }
        let created = Self.makeImpact(style: style, view: host)
        generator = created
        return created
    }

    private func selection() -> UISelectionFeedbackGenerator {
        if let selectionGenerator { return selectionGenerator }
        let created = Self.makeSelection(view: host)
        selectionGenerator = created
        return created
    }

    private static func makeImpact(
        style: UIImpactFeedbackGenerator.FeedbackStyle,
        view: UIView?
    ) -> UIImpactFeedbackGenerator {
        if #available(iOS 17.5, *), let view {
            return UIImpactFeedbackGenerator(style: style, view: view)
        }
        return UIImpactFeedbackGenerator(style: style)
    }

    private static func makeSelection(view: UIView?) -> UISelectionFeedbackGenerator {
        if #available(iOS 17.5, *), let view {
            return UISelectionFeedbackGenerator(view: view)
        }
        return UISelectionFeedbackGenerator()
    }
}
