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

    @Test func changingPlanesInvalidatesTargetsUntilTheNewLayoutExists() throws {
        let metrics = KeyboardMetrics.resolved(for: UITraitCollection {
            $0.verticalSizeClass = .regular
        })
        let grid = KeyGridView(metrics: metrics, palette: KeyboardPalette(isDark: false))
        grid.frame = CGRect(x: 0, y: 0, width: 393, height: metrics.gridHeight)
        grid.layoutIfNeeded()

        let oldKey = try #require(grid.keyViews.first { $0.spec.cap == .character("q") })
        let oldPoint = oldKey.frame.center
        #expect(grid.key(at: oldPoint, characterOnly: false)?.spec.cap == .character("q"))

        grid.plane = .numbers
        #expect(grid.key(at: oldPoint, characterOnly: false) == nil)

        grid.layoutIfNeeded()
        #expect(grid.key(at: oldPoint, characterOnly: false)?.spec.cap == .character("1"))
    }

    @Test func cancelledAndSlideCorrectedKeysReportOnlyTheFinalCommit() throws {
        let feedback = KeyboardFeedbackSpy()
        let delegate = KeyGridDelegateSpy()
        let metrics = KeyboardMetrics.resolved(for: UITraitCollection {
            $0.verticalSizeClass = .regular
        })
        let grid = KeyGridView(
            metrics: metrics,
            palette: KeyboardPalette(isDark: false),
            feedback: feedback
        )
        grid.delegate = delegate
        grid.shiftState = .off

        let initial = try #require(grid.keyViews.first { $0.spec.cap == .character("q") })
        let corrected = try #require(grid.keyViews.first { $0.spec.cap == .character("w") })
        grid.completeKeyInteraction(initial, shouldCommit: false)
        #expect(feedback.events.isEmpty)
        #expect(delegate.insertedText.isEmpty)

        grid.completeKeyInteraction(corrected, shouldCommit: true)
        #expect(feedback.events == [.textCommitted])
        #expect(delegate.insertedText == ["w"])
    }
}

private final class KeyboardFeedbackSpy: KeyboardFeedbackProviding {
    enum Event: Equatable { case textCommitted }

    private(set) var events: [Event] = []

    func textCommitted() { events.append(.textCommitted) }
    func keyActionCommitted() {}
    func selectionChanged() {}
    func action() {}
    func swipeBegan() {}
    func swipeCommitted() {}
}

private final class KeyGridDelegateSpy: KeyGridViewDelegate {
    private(set) var insertedText: [String] = []

    func keyGrid(_ grid: KeyGridView, didProduce output: KeyboardOutput) {
        if case let .text(text) = output { insertedText.append(text) }
    }

    func keyGridDidChangeShift(_ grid: KeyGridView) {}
    func keyGridShouldContinueDeleting(_ grid: KeyGridView) -> Bool { false }
}

private extension CGRect {
    var center: CGPoint { CGPoint(x: midX, y: midY) }
}
