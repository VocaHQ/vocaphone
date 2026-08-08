import UIKit

/// Concrete colours for the bar, resolved eagerly for one appearance exactly as
/// the key colours are, so a dark field inside a light app still gets a dark bar.
extension KeyboardPalette {
    /// One calm, solid accent per state. The bar needs a clear action colour,
    /// not decorative colour travel.
    func color(for accent: DictationAccent) -> UIColor {
        switch accent {
        case .brand:
            .systemBlue
        case .handoff:
            isDark ? rgb(0.64, 0.69, 0.98) : rgb(0.31, 0.36, 0.75)
        case .listening:
            isDark ? rgb(1, 0.45, 0.44) : rgb(0.82, 0.18, 0.19)
        case .working:
            isDark ? rgb(1, 0.72, 0.38) : rgb(0.72, 0.39, 0.05)
        case .ready:
            isDark ? rgb(0.43, 0.82, 0.61) : rgb(0.08, 0.5, 0.32)
        case .alert:
            isDark ? rgb(1, 0.48, 0.43) : rgb(0.72, 0.19, 0.15)
        case .locked:
            isDark ? rgb(0.58, 0.6, 0.63) : rgb(0.43, 0.45, 0.48)
        }
    }

    /// The single colour used for stateful controls, the dot, and waveform.
    func tint(for accent: DictationAccent) -> UIColor {
        color(for: accent)
    }

    var barBackground: UIColor {
        isDark
            ? UIColor(red: 0.135, green: 0.145, blue: 0.17, alpha: 1)
            : UIColor(red: 0.985, green: 0.99, blue: 1, alpha: 1)
    }

    var chipBackground: UIColor {
        isDark
            ? UIColor.white.withAlphaComponent(0.1)
            : UIColor.black.withAlphaComponent(0.045)
    }

    var secondaryControl: UIColor {
        isDark
            ? UIColor.white.withAlphaComponent(0.13)
            : UIColor.black.withAlphaComponent(0.06)
    }

    private func rgb(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat) -> UIColor {
        UIColor(red: red, green: green, blue: blue, alpha: 1)
    }
}

/// Geometry for one rendering of the bar. Everything scales with the traits so
/// landscape and iPad stop inheriting portrait iPhone sizing.
struct DictationBarMetrics: Equatable {
    var collapsedHeight: CGFloat
    var expandedHeight: CGFloat
    var horizontalInset: CGFloat
    var verticalInset: CGFloat
    var titleFontSize: CGFloat
    var bodyFontSize: CGFloat
    var controlHeight: CGFloat
    var waveformHeight: CGFloat
    var primaryWidth: CGFloat
    var primaryHeight: CGFloat
    var secondaryDiameter: CGFloat
    var cornerRadius: CGFloat
    /// A transcript preview is worth two lines where there is room for them.
    var messageLineLimit: Int

    func height(expanded: Bool) -> CGFloat {
        expanded ? expandedHeight : collapsedHeight
    }

    static func resolved(for traits: UITraitCollection) -> DictationBarMetrics {
        if traits.horizontalSizeClass == .regular, traits.verticalSizeClass == .regular {
            return DictationBarMetrics(
                collapsedHeight: 82,
                expandedHeight: 96,
                horizontalInset: 16,
                verticalInset: 12,
                titleFontSize: 17,
                bodyFontSize: 14,
                controlHeight: 34,
                waveformHeight: 40,
                primaryWidth: 138,
                primaryHeight: 50,
                secondaryDiameter: 40,
                cornerRadius: 24,
                messageLineLimit: 2
            )
        }
        if traits.verticalSizeClass == .compact {
            // Landscape phones have almost no height to spare, so the bar gives
            // up its breathing room before the keys give up theirs.
            return DictationBarMetrics(
                collapsedHeight: 58,
                expandedHeight: 58,
                horizontalInset: 11,
                verticalInset: 6,
                titleFontSize: 13,
                bodyFontSize: 11,
                controlHeight: 24,
                waveformHeight: 20,
                primaryWidth: 98,
                primaryHeight: 36,
                secondaryDiameter: 28,
                cornerRadius: 17,
                messageLineLimit: 1
            )
        }
        return DictationBarMetrics(
            collapsedHeight: 72,
            expandedHeight: 84,
            horizontalInset: 13,
            verticalInset: 9,
            titleFontSize: 15,
            bodyFontSize: 12.5,
            controlHeight: 30,
            waveformHeight: 34,
            primaryWidth: 116,
            primaryHeight: 44,
            secondaryDiameter: 34,
            cornerRadius: 21,
            messageLineLimit: 2
        )
    }
}
