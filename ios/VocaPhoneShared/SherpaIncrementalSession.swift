import Foundation

/// What an incremental session decoded, and whether any of the recording is
/// missing from it.
struct SherpaIncrementalResult: Sendable, Equatable {
    let transcript: SherpaTranscript

    /// True when a chunk that carried audio decoded to nothing.
    ///
    /// Those seconds are then simply absent, and nothing downstream can tell:
    /// the merge joins the chunks either side into text that reads as a whole
    /// sentence which happens to begin ten seconds into the recording. The
    /// attention families drop a long chunk often enough for this to be the
    /// difference between a transcript and a plausible-looking lie, so the
    /// caller re-decodes the file rather than shipping the hole.
    let droppedAudibleChunk: Bool

    static let empty = SherpaIncrementalResult(transcript: .empty, droppedAudibleChunk: false)
}

/// Consumes captured PCM while the microphone is still running. The WAV file
/// remains authoritative, but Sherpa's expensive offline work is spread over
/// the recording instead of making the user wait for the whole file at finish.
final class SherpaIncrementalSession: @unchecked Sendable {
    private let task: Task<SherpaIncrementalResult, Never>

    /// `decode` is handed one complete chunk at a time and must be safe to call
    /// off the main actor; the recognizer overload in the app target supplies
    /// the real one.
    init(chunks: AsyncStream<Data>, decode: @escaping @Sendable ([Float]) -> SherpaTranscript) {
        task = Task.detached(priority: .userInitiated) {
            await Self.transcribe(chunks: chunks, decode: decode)
        }
    }

    func finish() async -> SherpaIncrementalResult { await task.value }

    func cancel() { task.cancel() }

    private static func transcribe(
        chunks: AsyncStream<Data>,
        decode: @Sendable ([Float]) -> SherpaTranscript
    ) async -> SherpaIncrementalResult {
        var samples: [Float] = []
        samples.reserveCapacity(
            SherpaLongAudio.streamingWindowSeconds * SherpaLongAudio.sampleRate
        )
        var transcript = SherpaTranscript.empty
        var overlapsPrevious = false
        var droppedAudibleChunk = false
        // The gain a chunk is levelled with has to come from more than the chunk
        // itself: one gain per chunk moves the level at every boundary, and a
        // chunk that is all pause would be amplified into noise the model
        // transcribes as words. The peak over everything captured so far is the
        // closest a streaming chunk gets to the single gain the whole-file path
        // applies, and it only ever grows, so the gain only ever settles.
        var peak: Float = 0

        func consume(_ chunk: [Float]) {
            // Silence is judged on the capture as it arrived. The levelling
            // below multiplies a quiet recording by as much as eight, and a
            // floor meant for microphone levels reads amplified room tone as
            // speech — which buys a decode, the two more the empty-chunk
            // recovery adds on top, and then the whole-file re-run the flag
            // asks the caller for. All to transcribe a pause.
            guard !SherpaLongAudio.isEffectivelySilent(chunk) else { return }
            let levelled = SpeechAudioConditioning.condition(chunk, peak: peak)
            let decoded = decode(levelled)
            // Only a chunk long enough that the recovery has already tried and
            // failed is a hole worth re-reading the file for. Below that bar an
            // empty answer is routine — it is the retained overlap, or the
            // fragment of a word a recording ending just after a boundary
            // leaves — and treating it as a loss would spend a second pass over
            // the whole recording on almost every dictation.
            if decoded.text.isEmpty,
               chunk.count > SherpaLongAudio.minimumSuspectChunkSamples
            {
                droppedAudibleChunk = true
            }
            transcript = transcript.appending(decoded, deduplicateOverlap: overlapsPrevious)
        }

        func result() -> SherpaIncrementalResult {
            SherpaIncrementalResult(
                transcript: SherpaTranscript(
                    text: transcript.text.trimmingCharacters(in: .whitespacesAndNewlines),
                    language: transcript.language
                ),
                droppedAudibleChunk: droppedAudibleChunk
            )
        }

        for await data in chunks {
            guard !Task.isCancelled else { return result() }
            let incoming = Self.floatSamples(in: data)
            peak = incoming.reduce(peak) { max($0, abs($1)) }
            samples.append(contentsOf: incoming)

            while let split = SherpaLongAudio.nextStreamingSplit(samples) {
                consume(Array(samples[..<split.endExclusive]))
                samples.removeFirst(split.nextStart)
                overlapsPrevious = split.nextStart < split.endExclusive
            }
        }

        if !samples.isEmpty { consume(samples) }
        return result()
    }

    private static func floatSamples(in data: Data) -> [Float] {
        guard data.count >= MemoryLayout<Float>.stride else { return [] }
        return data.withUnsafeBytes { rawBuffer in
            let values = rawBuffer.bindMemory(to: Float.self)
            return Array(values)
        }
    }
}
