package com.rockmobile.voice

import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI states for a single voice interaction. Terminal states can safely start a new recording. */
sealed interface VoiceUiState {
    data object Idle : VoiceUiState
    data object PermissionRequired : VoiceUiState
    data object PermissionDenied : VoiceUiState
    data object PermissionPermanentlyDenied : VoiceUiState
    data object Recording : VoiceUiState
    data class Processing(val transcript: String? = null) : VoiceUiState
    data class Success(val transcript: String, val stationName: String) : VoiceUiState
    data class NoMatch(val transcript: String) : VoiceUiState
    data class NoPlayableStation(val transcript: String) : VoiceUiState
    data object ServerUnavailable : VoiceUiState
    data class RecoverableError(val message: String) : VoiceUiState
}

/** Minimal playback capability exposed to voice. It deliberately has no access to player internals. */
interface VoicePlaybackActions {
    /** Prevents speaker audio from being interpreted as the user's speech while recording. */
    fun beginVoiceCapture()
    /** Restores the user-selected speaker volume after the microphone is released. */
    fun endVoiceCapture()
    /**
     * Replaces the visible list with ranked candidates and returns a safe candidate for auto-play.
     * A locally known unavailable stream must never be returned here.
     */
    fun showCandidates(stations: List<Station>): Station?
    /** Starts the selected station using that same ranked list as the player queue. */
    fun play(station: Station, queue: List<Station>)
}

/** Coordinates microphone lifetime, server work and safe playback without blocking the UI thread. */
class VoiceCommandController(
    private val recorder: VoiceRecorder,
    private val client: VoiceCommandClient,
    private val baseUrl: () -> String,
    private val bearerToken: () -> String,
    private val playback: VoicePlaybackActions,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun requestPermission() { if (job?.isActive != true) _state.value = VoiceUiState.PermissionRequired }
    fun permissionResult(granted: Boolean, permanentlyDenied: Boolean = false) {
        if (granted) start() else _state.value = if (permanentlyDenied) VoiceUiState.PermissionPermanentlyDenied else VoiceUiState.PermissionDenied
    }

    fun start() {
        if (job?.isActive == true) return
        playback.beginVoiceCapture()
        _state.value = VoiceUiState.Recording
        job = scope.launch {
            try {
                val audio = withContext(ioDispatcher) { recorder.record() }
                processCapturedAudio(audio)
            } catch (cancelled: CancellationException) {
                _state.value = VoiceUiState.Idle
                throw cancelled
            } catch (error: RockserverVoiceException) {
                _state.value = if (error.code in setOf("speech_provider_unavailable", "speech_timeout", "search_timeout")) VoiceUiState.ServerUnavailable else VoiceUiState.RecoverableError(error.message ?: "Voice request failed")
            } catch (_: java.io.IOException) {
                _state.value = VoiceUiState.ServerUnavailable
            } catch (error: Throwable) {
                _state.value = VoiceUiState.RecoverableError(error.message ?: "Voice recording failed")
            } finally {
                playback.endVoiceCapture()
            }
        }
    }

    private suspend fun processCapturedAudio(audio: RecordedVoice) {
        _state.value = VoiceUiState.Processing()
        require(audio.pcmS16Le.isNotEmpty()) { "No speech was recorded" }
        val result = withContext(ioDispatcher) { client.resolve(baseUrl(), bearerToken(), audio) { transcript -> _state.value = VoiceUiState.Processing(transcript) } }
        when (result) {
            is VoiceResolution.StationMatch -> {
                val stationToPlay = playback.showCandidates(result.candidates)
                if (stationToPlay == null) {
                    _state.value = VoiceUiState.NoPlayableStation(result.transcript)
                } else {
                    playback.play(stationToPlay, result.candidates)
                    _state.value = VoiceUiState.Success(result.transcript, stationToPlay.name)
                }
            }
            is VoiceResolution.NoMatch -> _state.value = VoiceUiState.NoMatch(result.transcript)
        }
    }

    /** Optional early finish; the captured phrase is sent immediately. */
    fun finishRecording() { if (_state.value is VoiceUiState.Recording) recorder.finish() }

    fun cancel() { recorder.cancel(); job?.cancel(); job = null; playback.endVoiceCapture(); _state.value = VoiceUiState.Idle }
    fun dismiss() { if (job?.isActive != true) _state.value = VoiceUiState.Idle }

}
