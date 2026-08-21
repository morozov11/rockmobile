package com.rockmobile.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndDetectorTest {
    @Test fun stopsOnlyAfterSpeechIsFollowedByTheConfiguredSilence() {
        val detector = SpeechEndDetector(speechThreshold = 900, silenceDurationMs = 1_200)
        val silence = pcm(0)
        val speech = pcm(2_000)

        assertFalse(detector.shouldStop(silence, nowMs = 0))
        assertFalse(detector.shouldStop(speech, nowMs = 100))
        assertFalse(detector.shouldStop(silence, nowMs = 1_299))
        assertTrue(detector.shouldStop(silence, nowMs = 1_300))
    }

    @Test fun newSpeechExtendsTheRecordingInsteadOfCuttingOffANaturalPause() {
        val detector = SpeechEndDetector(speechThreshold = 900, silenceDurationMs = 1_200)
        val silence = pcm(0)
        val speech = pcm(2_000)

        detector.shouldStop(speech, nowMs = 100)
        assertFalse(detector.shouldStop(silence, nowMs = 1_000))
        assertFalse(detector.shouldStop(speech, nowMs = 1_100))
        assertFalse(detector.shouldStop(silence, nowMs = 2_299))
        assertTrue(detector.shouldStop(silence, nowMs = 2_300))
    }

    private fun pcm(value: Int): ByteArray = ByteArray(160) { index ->
        if (index % 2 == 0) (value and 0xff).toByte() else (value shr 8).toByte()
    }
}
