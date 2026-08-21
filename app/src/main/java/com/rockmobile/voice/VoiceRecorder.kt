package com.rockmobile.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

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
        try {
            recorder.startRecording()
            while (!finishRequested) {
                coroutineContext.ensureActive()
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    check(output.size() + count <= MAX_AUDIO_BYTES) { "Voice recording is too long" }
                    output.write(buffer, 0, count)
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
    }
}
