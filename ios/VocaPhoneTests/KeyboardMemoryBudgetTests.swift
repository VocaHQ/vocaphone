import Testing

/// When an off-screen keyboard ends its process instead of being reused.
struct KeyboardMemoryBudgetTests {
    /// The limit is whatever the device allows: the footprint plus what is
    /// left. Measured on an iPhone 14 Pro, that was 77 MB.
    @Test func recyclesPastItsShareOfTheLimit() {
        #expect(!KeyboardMemoryBudget.shouldRecycle(footprint: 30, available: 47))
        #expect(!KeyboardMemoryBudget.shouldRecycle(footprint: 45, available: 32))
        #expect(KeyboardMemoryBudget.shouldRecycle(footprint: 47, available: 30))
        #expect(KeyboardMemoryBudget.shouldRecycle(footprint: 62, available: 15))
    }

    /// A smaller phone's smaller limit moves the line with it.
    @Test func followsTheDevicesLimit() {
        #expect(KeyboardMemoryBudget.shouldRecycle(footprint: 30, available: 18))
        #expect(!KeyboardMemoryBudget.shouldRecycle(footprint: 30, available: 40))
    }

    @Test func anUnreadableLimitNeverRecycles() {
        #expect(!KeyboardMemoryBudget.shouldRecycle(footprint: 0, available: 0))
    }

    @Test func theFootprintIsReadable() {
        #expect((KeyboardMemoryBudget.footprintMegabytes ?? 0) > 0)
    }
}
