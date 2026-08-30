import Testing
import UIKit

@MainActor
struct KeyHitMapTests {
    private let map = KeyHitMap(targets: [
        KeyHitMap.Target(
            index: 0,
            frame: CGRect(x: 0, y: 0, width: 40, height: 40),
            hitRect: CGRect(x: 0, y: 0, width: 45, height: 40),
            isCharacter: true
        ),
        KeyHitMap.Target(
            index: 1,
            frame: CGRect(x: 45, y: 0, width: 40, height: 40),
            hitRect: CGRect(x: 40, y: 0, width: 45, height: 40),
            isCharacter: true
        ),
        KeyHitMap.Target(
            index: 2,
            frame: CGRect(x: 90, y: 0, width: 40, height: 40),
            hitRect: CGRect(x: 85, y: 0, width: 45, height: 40),
            isCharacter: false
        ),
    ])

    @Test func gutterBelongsToTheNearestVisibleCentre() {
        #expect(map.targetIndex(at: CGPoint(x: 41, y: 20), characterOnly: false) == 0)
        #expect(map.targetIndex(at: CGPoint(x: 44, y: 20), characterOnly: false) == 1)
    }

    @Test func exactBoundaryTieUsesStableLayoutOrder() {
        // Both targets claim 42.5 and their centres are equally distant.
        #expect(map.targetIndex(at: CGPoint(x: 42.5, y: 20), characterOnly: false) == 0)
    }

    @Test func characterOnlyQueriesNeverResolveAFunctionKey() {
        #expect(map.targetIndex(at: CGPoint(x: 110, y: 20), characterOnly: false) == 2)
        #expect(map.targetIndex(at: CGPoint(x: 110, y: 20), characterOnly: true) == nil)
    }

    @Test func pointsOutsideEveryEffectiveTargetDoNotResolve() {
        #expect(map.targetIndex(at: CGPoint(x: -1, y: 20), characterOnly: false) == nil)
        #expect(map.targetIndex(at: CGPoint(x: 200, y: 20), characterOnly: false) == nil)
    }
}
