import Foundation
import Testing

struct LocalModelFileRemovalTests {
    @Test func deletionReportsFailuresAndOnlySucceedsOnceFilesAreGone() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("model-delete-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("model".utf8).write(to: folder.appendingPathComponent("weights.onnx"))

        #expect(throws: CocoaError.self) {
            try LocalModelFileRemoval.remove(at: folder) { _ in
                throw CocoaError(.fileWriteNoPermission)
            }
        }
        #expect(FileManager.default.fileExists(atPath: folder.path))

        #expect(throws: CocoaError.self) {
            try LocalModelFileRemoval.remove(at: folder) { _ in }
        }
        #expect(FileManager.default.fileExists(atPath: folder.path))

        try LocalModelFileRemoval.remove(at: folder)
        #expect(!FileManager.default.fileExists(atPath: folder.path))
    }
}
