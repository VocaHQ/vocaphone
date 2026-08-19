package com.vocahq.vocaphone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.vocahq.vocaphone.core.DictationTone
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays a tone's start or stop cue. Empty PCM (Off) is a silent no-op so a
 * preview cannot crash. Haptics are separate: they fire even when the tone
 * is Off.
 */
class DictationTonePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val gate = Mutex()

    fun haptic() {
        val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            return
        }
        View(appContext).performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    suspend fun playStart(tone: DictationTone) = play(DictationToneSynth.start(tone))

    suspend fun playStop(tone: DictationTone) = play(DictationToneSynth.stop(tone))

    /** Start cue, then stop cue. Off stays silent. */
    suspend fun preview(tone: DictationTone) {
        playStart(tone)
        playStop(tone)
    }

    private suspend fun play(samples: ShortArray) {
        if (samples.isEmpty()) return
        gate.withLock {
            val track = try {
                buildTrack(samples)
            } catch (_: Throwable) {
                return@withLock
            }
            try {
                track.write(samples, 0, samples.size)
                track.play()
                delay(samples.size * 1000L / DictationToneSynth.SAMPLE_RATE + 24)
                track.stop()
            } catch (_: Throwable) {
                // A missed cue must never fail a dictation.
            } finally {
                track.release()
            }
        }
    }

    private fun buildTrack(samples: ShortArray): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(DictationToneSynth.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bytes = samples.size * 2
        val minBuffer = AudioTrack.getMinBufferSize(
            DictationToneSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(max(bytes, minBuffer))
            .build()
    }
}
