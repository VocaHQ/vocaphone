package com.vocahq.vocaphone.local

import com.vocahq.vocaphone.audio.SpeechAudioConditioning
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

/** The streaming result and the evidence required before it can be trusted. */
internal data class SherpaIncrementalResult(
    val transcript: SherpaTranscript,
    val droppedAudibleChunk: Boolean,
    val conditioningChanged: Boolean,
    val processingError: Throwable? = null,
) {
    /** A complete, stable result can bypass the post-capture WAV decode. */
    val isSafe: Boolean
        get() = processingError == null &&
            !droppedAudibleChunk &&
            !conditioningChanged &&
            transcript.text.isNotBlank()
}

/**
 * Decodes bounded Sherpa windows while AudioRecord continues capturing.
 *
 * This is a latency optimization, not a second source of truth. Every frame
 * is retained in the WAV as well. A changed running gain, an empty audible
 * chunk, a failed offer, or any native exception makes the caller use that
 * complete WAV instead.
 */
internal class SherpaIncrementalSession(
    scope: CoroutineScope,
    private val prepare: suspend () -> Unit,
    private val decode: suspend (FloatArray) -> SherpaTranscript,
) {
    private val accepting = AtomicBoolean(true)
    private val frames = Channel<ShortArray>(capacity = MAX_RECORDING_FRAMES)
    private val result: Deferred<SherpaIncrementalResult> =
        scope.async(Dispatchers.Default) { runSafely() }

    init {
        result.invokeOnCompletion { accepting.set(false) }
    }

    /** Non-blocking: this is called from the AudioRecord callback. */
    fun offer(frame: ShortArray): Boolean =
        accepting.get() && frames.trySend(frame).isSuccess

    suspend fun finish(): SherpaIncrementalResult {
        accepting.set(false)
        frames.close()
        return result.await()
    }

    fun cancel() {
        accepting.set(false)
        frames.cancel()
        result.cancel()
    }

    private suspend fun runSafely(): SherpaIncrementalResult = try {
        transcribe()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        SherpaIncrementalResult(
            transcript = SherpaTranscript.EMPTY,
            droppedAudibleChunk = true,
            conditioningChanged = true,
            processingError = error,
        )
    }

    private suspend fun transcribe(): SherpaIncrementalResult {
        prepare()
        val audio = FloatSampleBuffer(
            initialCapacity = SherpaLongAudio.STREAMING_WINDOW_SECONDS * SherpaLongAudio.SAMPLE_RATE,
        )
        var transcript = SherpaTranscript.EMPTY
        var overlapsPrevious = false
        var droppedAudibleChunk = false
        var conditioningChanged = false
        var lastDecodedPeak = 0f
        var loudestFrame = 0.0

        suspend fun consume(chunk: FloatArray) {
            val level = SherpaLongAudio.loudestFrame(chunk)
            if (SherpaLongAudio.isEffectivelySilent(level)) return

            // A running peak is the closest safe approximation to the
            // recording-wide gain. If it changes after a prior window, the
            // complete-WAV path must take over so every word gets one gain.
            if (lastDecodedPeak > 0f && audio.peak > lastDecodedPeak + PEAK_EPSILON) {
                conditioningChanged = true
            }
            val levelled = SpeechAudioConditioning.conditionStreaming(chunk, audio.peak)
            val decoded = decode(levelled)
            if (decoded.text.isEmpty() &&
                chunk.size > SherpaLongAudio.MIN_SUSPECT_CHUNK_SECONDS * SherpaLongAudio.SAMPLE_RATE &&
                SherpaLongAudio.carriesSpeech(level, loudestFrame)
            ) {
                droppedAudibleChunk = true
            }
            lastDecodedPeak = maxOf(lastDecodedPeak, audio.peak)
            loudestFrame = maxOf(loudestFrame, level)
            transcript = transcript.append(decoded, deduplicateOverlap = overlapsPrevious)
        }

        for (frame in frames) {
            audio.append(frame)
            while (true) {
                if (audio.size < SherpaLongAudio.STREAMING_WINDOW_SECONDS * SherpaLongAudio.SAMPLE_RATE) {
                    break
                }
                val available = audio.toFloatArray()
                val split = SherpaLongAudio.nextStreamingSplit(available) ?: break
                consume(available.copyOfRange(0, split.endExclusive))
                audio.discardPrefix(split.nextStart)
                overlapsPrevious = split.nextStart < split.endExclusive
            }
        }

        if (audio.size > 0) consume(audio.toFloatArray())
        return SherpaIncrementalResult(
            transcript = transcript.copy(text = transcript.text.trim()),
            droppedAudibleChunk = droppedAudibleChunk,
            conditioningChanged = conditioningChanged,
        )
    }

    private class FloatSampleBuffer(initialCapacity: Int) {
        private var samples = FloatArray(initialCapacity)
        var size: Int = 0
            private set
        var peak: Float = 0f
            private set

        fun append(frame: ShortArray) {
            ensureCapacity(size + frame.size)
            for (sample in frame) {
                val value = sample / 32_768f
                if (abs(value) > peak) peak = abs(value)
                samples[size++] = value
            }
        }

        fun discardPrefix(count: Int) {
            require(count in 0..size)
            samples.copyInto(
                destination = samples,
                destinationOffset = 0,
                startIndex = count,
                endIndex = size,
            )
            size -= count
        }

        fun toFloatArray(): FloatArray = samples.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= samples.size) return
            samples = samples.copyOf(maxOf(required, samples.size * 2))
        }
    }

    private companion object {
        // AudioCapture emits one 100 ms frame; the app stops at five minutes.
        const val MAX_RECORDING_FRAMES = 3_100
        const val PEAK_EPSILON = 0.0001f
    }
}
