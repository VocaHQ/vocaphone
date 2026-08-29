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
    static func applied(to samples: [Float], dither: Float) -> [Float] {
        guard dither > 0, !samples.isEmpty else { return samples }
        var generator = SherpaDitherGenerator(seed: 0x9E37_79B9_7F4A_7C15)
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

/// A small deterministic generator so a decode is reproducible across attempts.
///
/// The recovery ladder decodes the same waveform twice on purpose, and the
/// point of the repeat is a fresh native stream rather than fresh noise. Seeding
/// this identically each call keeps the difference between the two attempts on
/// the side being tested.
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

    /// A uniform in `(0, 1]`. Never zero, which `log` could not survive.
    private mutating func nextUnitInterval() -> Float {
        Float(next() >> 11) / Float(UInt64(1) << 53) + Float.ulpOfOne
    }

    mutating func nextNormalPair() -> (Float, Float) {
        let magnitude = (-2 * log(nextUnitInterval())).squareRoot()
        let angle = 2 * Float.pi * nextUnitInterval()
        return (magnitude * cos(angle), magnitude * sin(angle))
    }
}
