import Foundation

/// How a sherpa-onnx recognizer searches: its decoding method and beam width,
/// and whether it takes the user's words as transducer hotwords. All of it is
/// fixed when the recognizer is built. Mirrored by `SherpaSearch.kt` on
/// Android.
///
/// Two shapes only. The usual one is the family's own: greedy for every
/// family the app ships (see `SherpaFamily.supportsBeamSearch`). The other is
/// beam search with a BPE vocabulary, for a model with a
/// `LocalModelDescriptor.hotwordVocabularyFile` while the user has custom
/// words. Without words there is nothing to boost and the beam would only cost
/// time, so a user without a vocabulary keeps greedy search, and one who adds
/// or clears it gets the recognizer rebuilt once, never two held at once.
struct SherpaSearch: Equatable, Sendable {
    static let beamSearch = "modified_beam_search"

    let decodingMethod: String
    let maxActivePaths: Int32
    /// The BPE vocabulary's path, empty unless this search takes hotwords.
    let bpeVocabulary: String
    /// Ignored unless `takesHotwords`.
    let hotwordScore: Float

    var takesHotwords: Bool { !bpeVocabulary.isEmpty }

    /// `vocabulary` is the bundled `hotwordVocabularyFile`'s path when the
    /// dictation has hotwords for it, and nil otherwise. Only the NeMo
    /// transducer can use one; every other family keeps its own method,
    /// because a beam search it does not support ends the process.
    init(family: SherpaFamily, quality: TranscriptionQuality, vocabulary: String?) {
        if family == .nemoTransducer, let vocabulary, !vocabulary.isEmpty {
            decodingMethod = Self.beamSearch
            maxActivePaths = RecognitionHints.transducerBeamWidth
            bpeVocabulary = vocabulary
            hotwordScore = RecognitionHints.transducerHotwordScore
        } else {
            decodingMethod = family.decodingMethod(for: quality)
            maxActivePaths = quality.sherpaMaxActivePaths
            bpeVocabulary = ""
            hotwordScore = 0
        }
    }
}

/// Whether a decode with transducer hotwords stopped before the speech did.
/// Mirrored by `SherpaHotwordCheck` in `SherpaSearch.kt`.
///
/// sherpa-onnx 1.13.8's NeMo beam search boosts a hotword's pieces as they
/// are emitted and takes the bonus back only if a later piece leaves the
/// hotword. A hypothesis that has emitted part of a hotword can therefore keep
/// its bonus by emitting nothing at all, and it then outscores every
/// hypothesis that goes on transcribing: "add a quart of strong consom and
/// let all continue boiling" came back as "add a quart of strong Conflue"
/// with Confluence among 56 hotwords. What it leaves is audible speech after
/// the last token, and that is what this looks for: a stretch at least
/// `minimumTailSeconds` long, starting `marginSeconds` after the last token
/// began, whose loudest frame is at least `loudnessRatio` of the window's.
/// A window that trips it is decoded again without hotwords; trailing
/// silence, the usual end of a dictation, does not.
enum SherpaHotwordCheck {
    static let marginSeconds = 0.5
    static let minimumTailSeconds = 1.0
    static let loudnessRatio = 0.25

    /// `lastTokenSeconds` is when the last token began, or nil when the
    /// decode produced none, which makes the whole window the tail.
    static func stoppedEarly(_ samples: [Float], lastTokenSeconds: Double?) -> Bool {
        let loudest = SherpaLongAudio.loudestFrame(samples)
        guard !SherpaLongAudio.isEffectivelySilent(loudestFrame: loudest) else { return false }
        let rate = Double(SherpaLongAudio.sampleRate)
        let start = lastTokenSeconds.map { Int((max($0, 0) + marginSeconds) * rate) } ?? 0
        guard samples.count - start >= Int(minimumTailSeconds * rate) else { return false }
        let tail = Array(samples[start...])
        return SherpaLongAudio.loudestFrame(tail) >= loudnessRatio * loudest
    }
}
