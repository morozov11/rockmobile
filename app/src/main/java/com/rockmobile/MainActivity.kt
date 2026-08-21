package com.rockmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.repository.StationRepository
import com.rockmobile.data.stations.RockcastAssetStationSource
import com.rockmobile.data.stations.RockserverStationSource
import com.rockmobile.playback.PlaybackController
import com.rockmobile.settings.SettingsRepository
import com.rockmobile.ui.stations.StationsScreen
import com.rockmobile.ui.stations.StationsViewModel
import com.rockmobile.ui.stations.PlayerScreen
import com.rockmobile.ui.theme.RockmobileTheme
import com.rockmobile.voice.AndroidVoiceRecorder
import com.rockmobile.voice.RockserverVoiceClient
import com.rockmobile.voice.VoiceCommandController
import com.rockmobile.voice.VoicePlaybackActions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(this)
        val repository = StationRepository(
            RockserverStationSource(RockserverApi(), settings::rockserverUrl, settings::bearerToken),
            RockcastAssetStationSource(assets),
        )
        setContent {
            RockmobileTheme {
            val model: StationsViewModel = viewModel(factory = StationsViewModelFactory(repository))
            val state = model.state.collectAsStateWithLifecycle().value
            val playback = androidx.compose.runtime.remember { PlaybackController(this) }
            val voice = androidx.compose.runtime.remember {
                VoiceCommandController(
                    AndroidVoiceRecorder(), RockserverVoiceClient(), settings::rockserverUrl, settings::bearerToken,
                    object : VoicePlaybackActions {
                        override fun showCandidates(stations: List<com.rockmobile.domain.model.Station>) = model.showVoiceCandidates(stations)
                        override fun play(station: com.rockmobile.domain.model.Station, queue: List<com.rockmobile.domain.model.Station>) = playback.play(station, queue)
                    },
                    lifecycleScope,
                )
            }
            val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                voice.permissionResult(granted, !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            }
            var playerScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.cancel(); playback.release() } }
            val playbackState = playback.state.collectAsStateWithLifecycle().value
            val voiceState = voice.state.collectAsStateWithLifecycle().value
            if (playerScreen) PlayerScreen(playbackState, { playerScreen = false }, playback::toggle, playback::skipToPrevious, playback::skipToNext, playback::retry)
            else StationsScreen(state, playbackState, voiceState, model::retryRockserver, model::updateFilters, playback::play, playback::toggle,
                onVoice = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
                    else { voice.requestPermission(); microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                }, onFinishVoice = voice::finishRecording, onCancelVoice = voice::cancel, onDismissVoice = voice::dismiss,
            ) { playerScreen = true }
            }
        }
    }
}

private class StationsViewModelFactory(private val repository: StationRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = StationsViewModel(repository) as T
}
