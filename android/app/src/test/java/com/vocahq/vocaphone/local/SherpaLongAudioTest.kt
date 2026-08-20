package com.vocahq.vocaphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaLongAudioTest {
    @Test
    fun `short audio keeps one no-copy decode range`() {
        val chunks = SherpaLongAudio.chunks(FloatArray(12 * SherpaLongAudio.SAMPLE_RATE))

        assertEquals(listOf(SherpaAudioChunk(0, 192_000, false)), chunks)
    }

    @Test
    fun `continuous long audio is bounded and overlaps every boundary`() {
        val samples = FloatArray(52 * SherpaLongAudio.SAMPLE_RATE) { 0.2f }

        val chunks = SherpaLongAudio.chunks(samples)

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.endExclusive - it.start <= 14 * SherpaLongAudio.SAMPLE_RATE })
        assertTrue(chunks.drop(1).all { it.overlapsPrevious })
        assertTrue(chunks.zipWithNext().all { (left, right) -> right.start < left.endExclusive })
        assertEquals(samples.size, chunks.last().endExclusive)
    }

    @Test
    fun `the physical-device eighteen second regression is never one decode`() {
        val samples = FloatArray(18 * SherpaLongAudio.SAMPLE_RATE) { 0.2f }

        val chunks = SherpaLongAudio.chunks(samples)

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.endExclusive - it.start <= 12 * SherpaLongAudio.SAMPLE_RATE })
        assertEquals(samples.size, chunks.last().endExclusive)
    }

    @Test
    fun `a quiet boundary is preferred without surrendering overlap`() {
        val samples = FloatArray(52 * SherpaLongAudio.SAMPLE_RATE) { 0.2f }
        val silenceStart = 9 * SherpaLongAudio.SAMPLE_RATE
        val silenceEnd = silenceStart + SherpaLongAudio.SAMPLE_RATE
        java.util.Arrays.fill(samples, silenceStart, silenceEnd, 0f)

        val chunks = SherpaLongAudio.chunks(samples)
        val first = chunks.first()
        val second = chunks[1]

        assertFalse(first.overlapsPrevious)
        assertTrue(first.endExclusive in silenceStart..silenceEnd)
        assertTrue(second.overlapsPrevious)
        assertEquals(
            first.endExclusive -
                SherpaLongAudio.OVERLAP_MILLIS * SherpaLongAudio.SAMPLE_RATE / 1_000,
            second.start,
        )
    }

    @Test
    fun `one quiet frame inside speech is not trusted as a boundary`() {
        val samples = FloatArray(30 * SherpaLongAudio.SAMPLE_RATE) { 0.2f }
        java.util.Arrays.fill(
            samples,
            10 * SherpaLongAudio.SAMPLE_RATE,
            10 * SherpaLongAudio.SAMPLE_RATE + SherpaLongAudio.SAMPLE_RATE / 10,
            0f,
        )

        val first = SherpaLongAudio.chunks(samples).first()

        assertEquals(10 * SherpaLongAudio.SAMPLE_RATE, first.endExclusive)
    }

    @Test
    fun `an empty long chunk retries as overlapping short decodes`() {
        val decodedSizes = mutableListOf<Int>()
        var shortResult = 0
        val transcript = SherpaEmptyChunkRecovery.decode(
            samples = FloatArray(10 * SherpaLongAudio.SAMPLE_RATE),
            decodeOnce = { samples ->
                decodedSizes += samples.size
                SherpaTranscript(
                    if (samples.size > 6 * SherpaLongAudio.SAMPLE_RATE) {
                        ""
                    } else if (shortResult++ == 0) {
                        "first half"
                    } else {
                        "second half"
                    },
                )
            },
        )

        assertEquals("first half second half", transcript.text)
        assertEquals(listOf(160_000, 84_000, 84_000), decodedSizes)
    }

    @Test
    fun `an empty five second speech tail is retried`() {
        val decodedSizes = mutableListOf<Int>()
        var part = 0
        val transcript = SherpaEmptyChunkRecovery.decode(
            samples = FloatArray(5 * SherpaLongAudio.SAMPLE_RATE),
            decodeOnce = { samples ->
                decodedSizes += samples.size
                SherpaTranscript(
                    if (samples.size > 3 * SherpaLongAudio.SAMPLE_RATE) "" else "part ${++part}",
                )
            },
        )

        assertEquals("part 1 part 2", transcript.text)
        assertEquals(listOf(80_000, 44_000, 44_000), decodedSizes)
    }

    @Test
    fun `an empty long window can recover at quarter size`() {
        var part = 0
        val transcript = SherpaEmptyChunkRecovery.decode(
            samples = FloatArray(10 * SherpaLongAudio.SAMPLE_RATE),
            decodeOnce = { samples ->
                SherpaTranscript(
                    if (samples.size > 3 * SherpaLongAudio.SAMPLE_RATE) "" else "part ${++part}",
                )
            },
        )

        assertEquals("part 1 part 2 part 3 part 4", transcript.text)
    }

    @Test
    fun `overlapped words are merged once`() {
        assertEquals(
            "hello world again",
            SherpaTranscriptMerger.append("hello world", "world again"),
        )
        assertEquals(
            "Hello, there.",
            SherpaTranscriptMerger.append("Hello", "Hello, there."),
        )
        assertEquals(
            "yes yes",
            SherpaTranscriptMerger.append("yes", "yes", deduplicateOverlap = false),
        )
    }

    @Test
    fun `a phrase wider than the audio overlap is preserved as repetition`() {
        assertEquals(
            "start one two three four five one two three four five end",
            SherpaTranscriptMerger.append(
                "start one two three four five",
                "one two three four five end",
            ),
        )
    }
}
