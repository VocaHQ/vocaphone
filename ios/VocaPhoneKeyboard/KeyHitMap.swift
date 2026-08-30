import CoreGraphics

/// The effective targets for one laid-out keyboard plane.
///
/// Keys can visually leave a gutter between them while still claiming that
/// space for touch input. Keeping the selection rule separate from `UIView`
/// traversal makes that rule deterministic, testable, and shared by every
/// touch path. The visible geometry remains the source of truth; this type does
/// not invent personalised or language-aware correction.
struct KeyHitMap {
    struct Target: Equatable {
        /// Stable layout order, used only when a point is exactly equidistant
        /// from two visual centres.
        let index: Int
        let frame: CGRect
        let hitRect: CGRect
        let isCharacter: Bool
    }

    let targets: [Target]

    func targetIndex(at point: CGPoint, characterOnly: Bool) -> Int? {
        var winner: Target?
        var shortestDistance = CGFloat.greatestFiniteMagnitude

        for target in targets {
            guard (!characterOnly || target.isCharacter), target.hitRect.contains(point) else {
                continue
            }

            let distance = hypot(
                point.x - target.frame.midX,
                point.y - target.frame.midY
            )
            if distance < shortestDistance {
                winner = target
                shortestDistance = distance
                continue
            }

            // A gutter can be exactly on the midpoint between two keys. The
            // previous implementation happened to use subview iteration order;
            // layout order states that tie-break explicitly and keeps it stable
            // if the view hierarchy changes for previews or accessibility.
            if distance == shortestDistance,
               let currentWinner = winner,
               target.index < currentWinner.index
            {
                winner = target
            }
        }

        return winner?.index
    }
}
