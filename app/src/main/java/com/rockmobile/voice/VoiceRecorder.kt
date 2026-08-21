package com.rockmobile.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Captures bounded raw audio for one user-initiated voice request. */
interface VoiceRecorder {
    suspend fun record(): RecordedVoice
    fun finish()
    fun cancel()
}

/** Audio accepted by Rockserver's canonical streaming endpoint. */
data class RecordedVoice(val pcmS16Le: ByteArray, val sampleRateHz: Int = 16_000)

/** Android microphone implementation. It owns and always releases its [AudioRecord]. */
class AndroidVoiceRecorder : VoiceRecorder {
    @Volatile private var finishRequested = false
    @Volatile private var discard = false
    @Volatile private var active: AudioRecord? = null

    override suspend fun record(): RecordedVoice {
        finishRequested = false
        discard = false
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minimum > 0) { "Microphone does not support PCM 16 kHz mono" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setEncoding(ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL).build())
            .setBufferSizeInBytes(minimum * 2)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
        active = recorder
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(minimum)
        val speechDetector = SpeechEndDetector()
        val recordingStartedAt = SystemClock.elapsedRealtime()
        try {
            recorder.startRecording()
            while (!finishRequested) {
                coroutineContext.ensureActive()
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    check(output.size() + count <= MAX_AUDIO_BYTES) { "Voice recording is too long" }
                    output.write(buffer, 0, count)
                    val now = SystemClock.elapsedRealtime()
                    if (speechDetector.shouldStop(buffer, count, now)) finishRequested = true
                    check(now - recordingStartedAt <= MAX_RECORDING_DURATION_MS) { "No speech was detected" }
                } else if (count < 0 && !finishRequested) {
                    error("Microphone read failed")
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            if (active === recorder) active = null
        }
        check(!discard) { "Voice recording cancelled" }
        return RecordedVoice(output.toByteArray())
    }

    override fun finish() { finishRequested = true; runCatching { active?.stop() } }
    override fun cancel() { discard = true; finish() }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        const val MAX_RECORDING_DURATION_MS = 30_000L
    }
}

/**
 * Ends capture only after actual speech followed by a natural pause. Kept separate from
 * [AndroidVoiceRecorder] so the timing rule can be tested without Android microphone APIs.
 */
internal class SpeechEndDetector(
    private val speechThreshold: Int = 900,
    private val silenceDurationMs: Long = 1_200L,
) {
    private var speechStarted = false
    private var lastSpeechAtMs = 0L

    fun shouldStop(pcmS16Le: ByteArray, byteCount: Int = pcmS16Le.size, nowMs: Long): Boolean {
        if (rmsAmplitude(pcmS16Le, byteCount) >= speechThreshold) {
            speechStarted = true
            lastSpeechAtMs = nowMs
        }
        return speechStarted && nowMs - lastSpeechAtMs >= silenceDurationMs
    }

    private fun rmsAmplitude(bytes: ByteArray, byteCount: Int): Int {
        var energy = 0L
        var samples = 0
        var index = 0
        while (index + 1 < byteCount) {
            val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toLong() * sample
            samples++
            index += 2
        }
        return if (samples == 0) 0 else sqrt(energy.toDouble() / samples).toInt()
    }
}
