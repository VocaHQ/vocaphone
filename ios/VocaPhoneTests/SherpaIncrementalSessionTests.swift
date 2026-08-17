import Foundation
import Testing

/// The incremental path decodes a long recording as chunks while the microphone
/// is still open, so a chunk that comes back with no tokens takes its seconds
/// out of the transcript without leaving a trace in the text. These cover the
/// two things that keep those seconds: the caller is told, and the model is not
/// handed audio quieter than the whole-file path would give it.
struct SherpaIncrementalSessionTests {

    private static let sampleRate = SherpaLongAudio.sampleRate

    /// Speech-shaped enough to clear the silence floor, at whatever level the
    /// microphone is being asked to imitate.
    private static func tone(seconds: Double, amplitude: Float) -> [Float] {
        let count = Int(Double(sampleRate) * seconds)
        return (0..<count).map { index in
            amplitude * sin(Float(index) * 0.08) * (0.4 + 0.6 * abs(sin(Float(index) * 0.0004)))
        }
    }

    private static func stream(_ samples: [Float]) -> AsyncStream<Data> {
        AsyncStream { continuation in
            var index = 0
            while index < samples.count {
                let end = min(index + 1_600, samples.count)
                let slice = Array(samples[index..<end])
                continuation.yield(slice.withUnsafeBufferPointer { Data(buffer: $0) })
                index = end
            }
            continuation.finish()
        }
    }

    @Test func aChunkThatDecodesToNothingIsReported() async {
        let decoded = OSAllocatedCounter()
        let session = SherpaIncrementalSession(
            chunks: Self.stream(Self.tone(seconds: 25, amplitude: 0.4))
        ) { _ in
            // Exactly the failure this exists for: the first long chunk comes
            // back empty and every later one succeeds, so the merged text reads
            // as a whole sentence that starts ten seconds in.
            decoded.increment() == 1 ? SherpaTranscript(text: "") : SherpaTranscript(text: "later")
        }
        let result = await session.finish()

        #expect(decoded.value > 1)
        #expect(result.transcript.text == "later")
        #expect(result.droppedAudibleChunk)
    }

    @Test func aCompleteRunReportsNothingDropped() async {
        let session = SherpaIncrementalSession(
            chunks: Self.stream(Self.tone(seconds: 25, amplitude: 0.4))
        ) { _ in SherpaTranscript(text: "spoken") }
        let result = await session.finish()

        #expect(!result.droppedAudibleChunk)
        #expect(result.transcript.text.hasPrefix("spoken"))
    }

    @Test func silenceIsNotMistakenForADroppedChunk() async {
        let session = SherpaIncrementalSession(
            chunks: Self.stream([Float](repeating: 0, count: Self.sampleRate * 25))
        ) { _ in SherpaTranscript(text: "") }
        let result = await session.finish()

        // Nothing was said, so nothing was lost, and a whole-file retry of the
        // same silence would only cost the user time.
        #expect(result.transcript.text.isEmpty)
        #expect(!result.droppedAudibleChunk)
    }

    @Test func quietChunksReachTheModelLevelled() async {
        let peaks = OSAllocatedPeaks()
        let session = SherpaIncrementalSession(
            chunks: Self.stream(Self.tone(seconds: 25, amplitude: 0.05))
        ) { samples in
            peaks.record(samples.reduce(Float(0)) { max($0, abs($1)) })
            return SherpaTranscript(text: "spoken")
        }
        _ = await session.finish()

        // A phone on a desk records far below what an int8 model was trained
        // on. The whole-file path has always levelled that; the streaming path
        // used to hand the model the raw 0.05.
        #expect(peaks.recorded.allSatisfy { $0 > 0.3 })
    }

    @Test func theGainComesFromTheRecordingAndNotFromTheChunk() async {
        // A pause between two loud passages. Levelling it on its own peak would
        // amplify the room by eight and let the model transcribe the noise.
        var samples = Self.tone(seconds: 13, amplitude: 0.5)
        samples += [Float](repeating: 0.001, count: Self.sampleRate * 12)
        let peaks = OSAllocatedPeaks()
        let session = SherpaIncrementalSession(chunks: Self.stream(samples)) { chunk in
            peaks.record(chunk.reduce(Float(0)) { max($0, abs($1)) })
            return SherpaTranscript(text: "spoken")
        }
        _ = await session.finish()

        #expect(peaks.recorded.count > 1)
        #expect(peaks.recorded.last! < 0.01)
    }
}

/// The decode closure runs off the main actor, so the counters it touches have
/// to be safe to share.
private final class OSAllocatedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    @discardableResult
    func increment() -> Int {
        lock.lock()
        defer { lock.unlock() }
        count += 1
        return count
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }
}

private final class OSAllocatedPeaks: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [Float] = []

    func record(_ peak: Float) {
        lock.lock()
        defer { lock.unlock() }
        values.append(peak)
    }

    var recorded: [Float] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}
