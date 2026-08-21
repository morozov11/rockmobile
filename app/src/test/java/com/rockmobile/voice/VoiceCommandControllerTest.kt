package com.rockmobile.voice

import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCommandControllerTest {
    private val station = Station("jazz", "Quiet Jazz", "https://example.test/jazz")

    @Test fun permissionDeniedStates_areExplicit() = runTest {
        val controller = controller(this)
        controller.requestPermission(); assertEquals(VoiceUiState.PermissionRequired, controller.state.value)
        controller.permissionResult(false); assertEquals(VoiceUiState.PermissionDenied, controller.state.value)
        controller.permissionResult(false, permanentlyDenied = true); assertEquals(VoiceUiState.PermissionPermanentlyDenied, controller.state.value)
    }

    @Test fun recordedStationMatch_startsPlayback_andReportsTranscript() = runTest {
        val played = mutableListOf<Station>()
        val captureEvents = mutableListOf<String>()
        val controller = controller(this, playback = { played += it }, captureEvents = captureEvents)
        controller.start(); advanceUntilIdle()
        assertEquals(listOf(station), played)
        assertEquals(VoiceUiState.Success("включи джаз", "Quiet Jazz"), controller.state.value)
        assertEquals(listOf("begin", "end"), captureEvents)
    }

    @Test fun knownUnavailableSelectedStation_startsFirstAvailableCandidate() = runTest {
        val unavailable = Station("offline", "Offline", "https://example.test/offline")
        val available = Station("online", "Online", "https://example.test/online")
        val played = mutableListOf<Station>()
        val controller = controller(
            this,
            client = client { VoiceResolution.StationMatch("play radio", unavailable, listOf(unavailable, available)) },
            playback = { played += it },
            candidateSelector = { candidates -> candidates.firstOrNull { it.id != unavailable.id } },
        )

        controller.start(); advanceUntilIdle()

        assertEquals(listOf(available), played)
        assertEquals(VoiceUiState.Success("play radio", "Online"), controller.state.value)
    }

    @Test fun allKnownUnavailableCandidates_doNotStartPlayback() = runTest {
        val unavailable = Station("offline", "Offline", "https://example.test/offline")
        val played = mutableListOf<Station>()
        val controller = controller(
            this,
            client = client { VoiceResolution.StationMatch("play radio", unavailable, listOf(unavailable)) },
            playback = { played += it },
            candidateSelector = { null },
        )

        controller.start(); advanceUntilIdle()

        assertTrue(played.isEmpty())
        assertEquals(VoiceUiState.NoPlayableStation("play radio"), controller.state.value)
    }

    @Test fun noMatch_networkAndMalformedFailures_doNotTouchPlayback() = runTest {
        val played = mutableListOf<Station>()
        val noMatch = controller(this, client = client { VoiceResolution.NoMatch("найди радио") }, playback = { played += it })
        noMatch.start(); advanceUntilIdle(); assertTrue(noMatch.state.value is VoiceUiState.NoMatch)
        val unavailable = controller(this, client = client { throw java.io.IOException("offline") }, playback = { played += it })
        unavailable.start(); advanceUntilIdle(); assertEquals(VoiceUiState.ServerUnavailable, unavailable.state.value)
        val malformed = controller(this, client = client { throw IllegalArgumentException("malformed response") }, playback = { played += it })
        malformed.start(); advanceUntilIdle(); assertTrue(malformed.state.value is VoiceUiState.RecoverableError)
        assertTrue(played.isEmpty())
    }

    @Test fun recordingCompletion_sendsAudioWithoutAnotherTap() = runTest {
        val received = mutableListOf<ByteArray>()
        val recorder = FakeRecorder()
        val controller = controller(this, recorder = recorder, client = object : VoiceCommandClient {
            override suspend fun resolve(baseUrl: String, bearerToken: String, audio: RecordedVoice, onTranscript: (String) -> Unit): VoiceResolution {
                received += audio.pcmS16Le
                return VoiceResolution.NoMatch("test")
            }
        })
        controller.start(); advanceUntilIdle()
        assertEquals(listOf<Byte>(1, 2), received.single().toList())
    }

    @Test fun cancelWhileRecording_releasesRecorder_andLeavesIdle() = runTest {
        val recorder = BlockingRecorder()
        val controller = controller(this, recorder = recorder)
        controller.start(); assertEquals(VoiceUiState.Recording, controller.state.value)
        controller.cancel(); advanceUntilIdle()
        assertTrue(recorder.cancelled); assertEquals(VoiceUiState.Idle, controller.state.value)
    }

    private fun controller(scope: CoroutineScope,
        recorder: VoiceRecorder = FakeRecorder(),
        client: VoiceCommandClient = client { VoiceResolution.StationMatch("включи джаз", station, listOf(station)) },
        playback: (Station) -> Unit = {},
        captureEvents: MutableList<String> = mutableListOf(),
        candidateSelector: (List<Station>) -> Station? = { it.firstOrNull() },
    ) = VoiceCommandController(recorder, client, { "http://server.test" }, { "token" }, object : VoicePlaybackActions {
        override fun beginVoiceCapture() { captureEvents += "begin" }
        override fun endVoiceCapture() { captureEvents += "end" }
        override fun showCandidates(stations: List<Station>) = candidateSelector(stations)
        override fun play(station: Station, queue: List<Station>) = playback(station)
    }, scope, UnconfinedTestDispatcher())

    private fun client(result: suspend () -> VoiceResolution) = object : VoiceCommandClient {
        override suspend fun resolve(baseUrl: String, bearerToken: String, audio: RecordedVoice, onTranscript: (String) -> Unit): VoiceResolution = result()
    }
    private class FakeRecorder : VoiceRecorder {
        override suspend fun record() = RecordedVoice(byteArrayOf(1, 2))
        override fun finish() = Unit
        override fun cancel() = Unit
    }
    private class BlockingRecorder : VoiceRecorder {
        var cancelled = false
        override suspend fun record(): RecordedVoice { awaitCancellation() }
        override fun finish() = Unit
        override fun cancel() { cancelled = true }
    }
}
