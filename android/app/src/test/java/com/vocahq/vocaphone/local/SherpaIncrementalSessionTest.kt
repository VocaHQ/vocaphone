package com.vocahq.vocaphone.local

import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incremental path decodes a long recording as chunks while the microphone
 * is still open, so a chunk that comes back with no tokens takes its seconds out
 * of the transcript without leaving a trace in the text. These cover the two
 * things that keep those seconds: the caller is told, and the model is not
 * handed audio quieter than the whole-file path would give it.
 *
 * Mirrors the iOS client's `SherpaIncrementalSessionTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SherpaIncrementalSessionTest {

    /**
     * Speech-shaped enough to clear the silence floor, at whatever level the
     * microphone is being asked to imitate.
     */
    private fun tone(seconds: Double, amplitude: Float): ShortArray {
        val count = (SherpaLongAudio.SAMPLE_RATE * seconds).toInt()
        return ShortArray(count) { index ->
            val envelope = 0.4f + 0.6f * abs(sin(index * 0.0004f))
            (amplitude * sin(index * 0.08f) * envelope * 32_767f).toInt().toShort()
        }
    }

    private suspend fun run(
        samples: ShortArray,
        scope: TestScope,
        decode: (FloatArray) -> SherpaTranscript,
    ): SherpaIncrementalResult {
        val session = SherpaIncrementalSession(
            scope = scope,
            prepare = {},
            decode = { decode(it) },
        )
        var index = 0
        while (index < samples.size) {
            val end = minOf(index + 1_600, samples.size)
            assertTrue(session.offer(samples.copyOfRange(index, end)))
            index = end
        }
        return session.finish()
    }

    @Test
    fun aChunkThatDecodesToNothingIsReported() = runTest {
        var decoded = 0
        val result = run(tone(seconds = 25.0, amplitude = 0.4f), this) {
            decoded += 1
            // Exactly the failure this exists for: the first long chunk comes
            // back empty and every later one succeeds, so the merged text reads
            // as a whole sentence that starts ten seconds in.
            if (decoded == 1) SherpaTranscript("") else SherpaTranscript("later")
        }

        assertTrue(decoded > 1)
        assertEquals("later", result.transcript.text)
        assertTrue(result.droppedAudibleChunk)
    }

    @Test
    fun aCompleteRunReportsNothingDropped() = runTest {
        val result = run(tone(seconds = 25.0, amplitude = 0.4f), this) {
            SherpaTranscript("spoken")
        }

        assertFalse(result.droppedAudibleChunk)
        assertTrue(result.transcript.text.startsWith("spoken"))
    }

    @Test
    fun silenceIsNotMistakenForADroppedChunk() = runTest {
        val result = run(ShortArray(SherpaLongAudio.SAMPLE_RATE * 25), this) {
            SherpaTranscript("")
        }

        // Nothing was said, so nothing was lost, and a whole-file retry of the
        // same silence would only cost the user time.
        assertTrue(result.transcript.text.isEmpty())
        assertFalse(result.droppedAudibleChunk)
    }

    @Test
    fun quietChunksReachTheModelLevelled() = runTest {
        val peaks = mutableListOf<Float>()
        run(tone(seconds = 25.0, amplitude = 0.05f), this) { chunk ->
            peaks += chunk.fold(0f) { peak, sample -> maxOf(peak, abs(sample)) }
            SherpaTranscript("spoken")
        }

        // A phone on a desk records far below what an int8 model was trained
        // on. The whole-file path has always levelled that; the streaming path
        // used to hand the model the raw 0.05.
        assertTrue(peaks.isNotEmpty())
        assertTrue(peaks.all { it > 0.3f })
    }

    @Test
    fun theGainComesFromTheRecordingAndNotFromTheChunk() = runTest {
        // A pause between two loud passages. Levelling it on its own peak would
        // amplify the room by eight and let the model transcribe the noise.
        val loud = tone(seconds = 13.0, amplitude = 0.5f)
        val quiet = ShortArray(SherpaLongAudio.SAMPLE_RATE * 12) { 32 }
        val peaks = mutableListOf<Float>()
        run(loud + quiet, this) { chunk ->
            peaks += chunk.fold(0f) { peak, sample -> maxOf(peak, abs(sample)) }
            SherpaTranscript("spoken")
        }

        assertTrue(peaks.size > 1)
        assertTrue(peaks.last() < 0.01f)
    }
}
