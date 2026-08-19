package com.vocahq.vocaphone.audio

import com.vocahq.vocaphone.core.DictationTone
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * In-memory start/stop PCM for [DictationTone]. Off is an empty buffer, which
 * the player treats as silence.
 */
object DictationToneSynth {
    const val SAMPLE_RATE = 16_000

    fun start(tone: DictationTone): ShortArray = when (tone) {
        DictationTone.LIFT -> glide(F4, A4, seconds = 0.32f, amplitude = 0.16f)
        DictationTone.FLICK -> glide(F4, A4, seconds = 0.14f, amplitude = 0.16f)
        DictationTone.EMBER -> glide(G3, C4, seconds = 0.36f, amplitude = 0.18f)
        DictationTone.STEP -> ticks(floatArrayOf(C4, E4), tickSeconds = 0.045f, gapSeconds = 0.04f)
        DictationTone.VOCA -> swell(C4, G4, seconds = 0.28f, amplitude = 0.14f)
        DictationTone.SOFT -> ticks(floatArrayOf(C5), tickSeconds = 0.028f, gapSeconds = 0f, amplitude = 0.08f)
        DictationTone.CHIRP -> glide(1200f, 1760f, seconds = 0.08f, amplitude = 0.12f)
        DictationTone.SCALE -> ticks(floatArrayOf(A4, C5), tickSeconds = 0.05f, gapSeconds = 0.025f)
        DictationTone.DROP -> glide(G3, E3, seconds = 0.16f, amplitude = 0.16f)
        DictationTone.GLASS -> ping(C7, seconds = 0.09f, amplitude = 0.10f)
        DictationTone.OFF -> ShortArray(0)
    }

    fun stop(tone: DictationTone): ShortArray = when (tone) {
        DictationTone.LIFT -> glide(A4, F4, seconds = 0.32f, amplitude = 0.16f)
        DictationTone.FLICK -> glide(A4, F4, seconds = 0.14f, amplitude = 0.16f)
        DictationTone.EMBER -> glide(C4, G3, seconds = 0.36f, amplitude = 0.18f)
        DictationTone.STEP -> ticks(floatArrayOf(E4, C4), tickSeconds = 0.045f, gapSeconds = 0.04f)
        DictationTone.VOCA -> swell(A3, E4, seconds = 0.28f, amplitude = 0.13f)
        DictationTone.SOFT -> ticks(floatArrayOf(A4), tickSeconds = 0.028f, gapSeconds = 0f, amplitude = 0.07f)
        DictationTone.CHIRP -> glide(1760f, 980f, seconds = 0.09f, amplitude = 0.11f)
        DictationTone.SCALE -> ticks(floatArrayOf(C5, A4), tickSeconds = 0.05f, gapSeconds = 0.025f)
        DictationTone.DROP -> glide(E3, C3, seconds = 0.22f, amplitude = 0.16f)
        DictationTone.GLASS -> ping(G6, seconds = 0.10f, amplitude = 0.09f)
        DictationTone.OFF -> ShortArray(0)
    }

    private fun glide(fromHz: Float, toHz: Float, seconds: Float, amplitude: Float): ShortArray {
        val count = sampleCount(seconds)
        val samples = ShortArray(count)
        var phase = 0.0
        for (index in 0 until count) {
            val progress = index / (count - 1).toFloat().coerceAtLeast(1f)
            val glide = progress * progress * (3f - 2f * progress)
            val hz = fromHz + (toHz - fromHz) * glide
            phase += TWO_PI * hz / SAMPLE_RATE
            samples[index] = pcm(sin(phase) * amplitude * envelope(index, count))
        }
        return samples
    }

    private fun swell(rootHz: Float, upperHz: Float, seconds: Float, amplitude: Float): ShortArray {
        val count = sampleCount(seconds)
        val samples = ShortArray(count)
        var rootPhase = 0.0
        var upperPhase = 0.0
        for (index in 0 until count) {
            rootPhase += TWO_PI * rootHz / SAMPLE_RATE
            upperPhase += TWO_PI * upperHz / SAMPLE_RATE
            val mix = (sin(rootPhase) + sin(upperPhase)) * 0.5
            samples[index] = pcm(mix * amplitude * envelope(index, count))
        }
        return samples
    }

    private fun ticks(
        frequencies: FloatArray,
        tickSeconds: Float,
        gapSeconds: Float,
        amplitude: Float = 0.12f,
    ): ShortArray {
        val tickCount = sampleCount(tickSeconds)
        val gapCount = sampleCount(gapSeconds)
        val total = frequencies.size * tickCount + (frequencies.size - 1).coerceAtLeast(0) * gapCount
        val samples = ShortArray(total)
        var offset = 0
        for ((index, hz) in frequencies.withIndex()) {
            var phase = 0.0
            for (i in 0 until tickCount) {
                phase += TWO_PI * hz / SAMPLE_RATE
                samples[offset + i] = pcm(sin(phase) * amplitude * envelope(i, tickCount))
            }
            offset += tickCount
            if (index < frequencies.lastIndex) offset += gapCount
        }
        return samples
    }

    private fun ping(hz: Float, seconds: Float, amplitude: Float): ShortArray {
        val count = sampleCount(seconds)
        val samples = ShortArray(count)
        var phase = 0.0
        for (index in 0 until count) {
            phase += TWO_PI * hz / SAMPLE_RATE
            val decay = exp(-6.0 * index / count)
            samples[index] = pcm(sin(phase) * amplitude * decay)
        }
        return samples
    }

    private fun sampleCount(seconds: Float): Int = (SAMPLE_RATE * seconds).toInt().coerceAtLeast(1)

    private fun envelope(index: Int, count: Int): Float {
        val fade = min(48, count / 4).coerceAtLeast(1)
        val attack = min(1f, index / fade.toFloat())
        val release = min(1f, (count - 1 - index) / fade.toFloat())
        return min(attack, release)
    }

    private fun pcm(value: Double): Short =
        (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    private const val TWO_PI = 2.0 * PI
    private const val C3 = 130.81f
    private const val E3 = 164.81f
    private const val G3 = 196.00f
    private const val A3 = 220.00f
    private const val C4 = 261.63f
    private const val E4 = 329.63f
    private const val F4 = 349.23f
    private const val G4 = 392.00f
    private const val A4 = 440.00f
    private const val C5 = 523.25f
    private const val G6 = 1567.98f
    private const val C7 = 2093.00f
}
