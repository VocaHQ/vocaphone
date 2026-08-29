import Foundation

/// Adds the tiny feature noise that keeps a zero-dither Parakeet from answering
/// valid speech with no tokens at all.
///
/// Kaldi's feature extractor adds `dither * RandGauss()` to every waveform
/// sample before framing. Its purpose is not audible: an exactly-constant
/// window — digital silence, a clipped plateau, a synthetic pad — makes the log
/// energy of that frame collapse, and NeMo TDT has been observed to return an
/// empty result for a whole recording because of it (sherpa-onnx #2258).
///
/// The Android client asks the runtime for this by setting
/// `FeatureConfig.dither`. The pinned iOS runtime (sherpa-onnx v1.12.34) has no
/// `dither` field on `SherpaOnnxFeatureConfig`, so there is nothing to set;
/// applying the same Gaussian to the samples on the way in is numerically the
/// same thing one stage earlier, and is what keeps the two platforms honest
/// until the runtime upgrade is evaluated on its own.
///
/// This matters most for the empty-result recovery ladder, which pads a short
/// recording with silence. Without dither that padding is a run of exact zeros
/// — precisely the input #2258 describes — so the retry meant to rescue the
/// recording would be the attempt least likely to succeed.
enum SherpaFeatureDither {

    /// Returns `samples` with Gaussian noise of standard deviation `dither`.
    ///
    /// A non-positive `dither` returns the samples untouched, so the families
    /// that do not need this pay one comparison and no copy.
    ///
    /// The noise is drawn fresh on every call, as Kaldi's is. That is what makes
    /// the recovery ladder's second attempt a real second attempt: the runtime
    /// dithers a Parakeet decode differently each time it runs, so re-deciding
    /// the same waveform is not guaranteed to reproduce the same no-token
    /// answer. Seeding this identically each call would hand the model
    /// byte-identical features and make that rung of the ladder cost a decode
    /// to learn nothing.
    static func applied(to samples: [Float], dither: Float) -> [Float] {
        guard dither > 0, !samples.isEmpty else { return samples }
        var generator = SherpaDitherGenerator(seed: UInt64.random(in: UInt64.min...UInt64.max))
        var result = samples
        var index = 0
        // Box-Muller returns two independent normals per pair of uniforms, so
        // the transcendentals cost half of what a per-sample draw would.
        while index < result.count {
            let (first, second) = generator.nextNormalPair()
            result[index] += dither * first
            if index + 1 < result.count {
                result[index + 1] += dither * second
            }
            index += 2
        }
        return result
    }
}

/// SplitMix64, and a Box-Muller pair on top of it.
///
/// Every step of the uniform is computed in `Double` and bounded to `(0, 1]` by
/// construction. Doing it in `Float` is what a `Float` cannot survive here: a
/// 53-bit draw rounds up to exactly 1 once in about 2^24 values, one nudge past
/// that makes `log` positive, and the square root of the negative that follows
/// is a `NaN` written straight into the waveform. At 16 kHz that is a coin flip
/// every couple of decodes, and a single `NaN` sample is an empty transcript —
/// the failure this file exists to prevent.
private struct SherpaDitherGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    private mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// A uniform in `(0, 1]`, exactly. 52 bits leaves `Double` a spare bit, so
    /// `bits + 1` is exact and the largest result is 1 rather than a hair above.
    private mutating func nextUnitInterval() -> Double {
        (Double(next() >> 12) + 1) / Double(UInt64(1) << 52)
    }

    mutating func nextNormalPair() -> (Float, Float) {
        // `log(1)` is 0, which is a magnitude of zero and not a special case.
        let magnitude = (-2 * Foundation.log(nextUnitInterval())).squareRoot()
        let angle = 2 * Double.pi * nextUnitInterval()
        return (Float(magnitude * cos(angle)), Float(magnitude * sin(angle)))
    }
}
