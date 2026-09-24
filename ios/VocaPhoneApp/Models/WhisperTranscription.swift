import Foundation
import WhisperKit

/// Decode one window at a time and propagate every window's error. WhisperKit
/// WhisperKit's built-in VAD path runs windows concurrently on iOS and drops
/// failed chunks when collecting results, potentially returning partial success.
@MainActor
enum WhisperTranscription {
    /// Runs `operation`, and after any failure but cancellation, calls
    /// `prepareRetry` and runs it exactly once more.
    ///
    /// One retry, because the failure this exists for is the first dictation
    /// after the model was dropped: a cold Core ML load in a backgrounded app
    /// that fails where the very next attempt succeeds. `prepareRetry` is where
    /// the caller throws the half-built engine away so the second attempt starts
    /// from nothing. A second failure is a real one and is not hidden.
    static func retryingOnce<T>(
        _ operation: () async throws -> T,
        prepareRetry: (Error) -> Void
    ) async throws -> T {
        do {
            return try await operation()
        } catch {
            if error is CancellationError || Task.isCancelled { throw error }
            prepareRetry(error)
            try Task.checkCancellation()
            return try await operation()
        }
    }

    static func transcribe(
        samples: [Float],
        options: DecodingOptions,
        decode: ([Float], DecodingOptions) async throws -> [TranscriptionResult]
    ) async throws -> [TranscriptionResult] {
        // Keep a final sub-second tail too; it can contain the last spoken word.
        let chunks = try await VADAudioChunker(windowPadding: 0).chunkAll(
            audioArray: samples,
            maxChunkLength: 30 * WhisperKit.sampleRate,
            decodeOptions: options
        )
        var windowOptions = options
        windowOptions.clipTimestamps = []
        windowOptions.chunkingStrategy = nil
        windowOptions.concurrentWorkerCount = 1
        var results: [TranscriptionResult] = []
        for chunk in chunks {
            try Task.checkCancellation()
            var samples = chunk.audioSamples
            // Whisper's seek loop skips clips shorter than windowClipTime.
            // Pad an audible short final window so its last word is decoded.
            let minimumSamples = Int((windowOptions.windowClipTime + 0.1) * Float(WhisperKit.sampleRate))
            if samples.count < minimumSamples {
                samples += [Float](repeating: 0, count: minimumSamples - samples.count)
            }
            let decoded = try await decode(samples, windowOptions)
            try Task.checkCancellation()
            results.append(contentsOf: decoded)
        }
        return results
    }
}
