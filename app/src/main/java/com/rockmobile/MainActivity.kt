package com.rockmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
            val model: StationsViewModel = viewModel(factory = StationsViewModelFactory(repository))
            val state = model.state.collectAsStateWithLifecycle().value
            val playback = androidx.compose.runtime.remember { PlaybackController(this) }
            var playerScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { playback.release() } }
            val playbackState = playback.state.collectAsStateWithLifecycle().value
            if (playerScreen) PlayerScreen(playbackState, { playerScreen = false }, playback::toggle, playback::skipToPrevious, playback::skipToNext, playback::retry)
            else StationsScreen(state, playbackState, model::retryRockserver, model::updateFilters, playback::play, playback::toggle) { playerScreen = true }
        }
    }
}

private class StationsViewModelFactory(private val repository: StationRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = StationsViewModel(repository) as T
}
