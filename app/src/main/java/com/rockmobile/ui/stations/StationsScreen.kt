package com.rockmobile.ui.stations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.devicecontrol.LivePlaybackStatusUi
import com.rockmobile.devicecontrol.LiveTargetPresentation
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState
import com.rockmobile.voice.VoiceUiState

/**
 * Station catalog (ТЗ §5.1): search + quick genre/favourite chips, a music-focused station
 * list without per-device buttons, and a sticky mini-player fed by the same live state as
 * the station screen. Tapping a row opens that station's screen.
 */
@Composable
fun StationsScreen(
    state: StationsUiState,
    playback: PlaybackState,
    voice: VoiceUiState,
    retry: () -> Unit,
    updateFilters: ((StationFilters) -> StationFilters) -> Unit,
    play: (Station, List<Station>) -> Unit,
    toggle: () -> Unit,
    localStop: () -> Unit,
    onVoice: () -> Unit,
    onFinishVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onDismissVoice: () -> Unit,
    openStation: (String) -> Unit,
    personal: PersonalData,
    toggleFavourite: (Station) -> Unit,
    openAccount: () -> Unit,
    openDevices: () -> Unit,
    accountConnected: Boolean = false,
    clearHistory: () -> Unit,
    liveRemote: LiveTargetPresentation? = null,
    liveRemoteTargetName: String? = null,
    remotePlay: (String) -> Unit = {},
    remoteStop: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    var favouritesOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val remotePlayingId = liveRemote
        ?.takeIf { it.status == LivePlaybackStatusUi.Playing || it.status == LivePlaybackStatusUi.Buffering }
        ?.confirmedStationId
    val currentStationId = remotePlayingId ?: playback.station?.id

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                RockHeader(retry, openAccount, openDevices, accountConnected)
                Spacer(Modifier.height(6.dp))
                when (state) {
                    StationsUiState.Loading -> LoadingState()
                    is StationsUiState.Error -> ErrorState(state.message, retry)
                    is StationsUiState.Content -> {
                        state.fallbackReason?.let { FallbackBanner(it) }
                        CatalogueHeader(state.catalogue.source.name, state.stations.size)
                        PersonalSummary(
                            personal,
                            onOpenFavourites = { favouritesOpen = true },
                            onOpenHistory = { historyOpen = true },
                        )
                        SearchAndFilters(state, voice, updateFilters, onVoice, onFinishVoice, onCancelVoice)
                        VoiceStatusBar(voice, onCancelVoice, onDismissVoice)
                        LiveMiniPlayer(
                            remote = liveRemote,
                            remoteTargetName = liveRemoteTargetName,
                            local = playback,
                            stations = state.catalogue.stations,
                            openStation = openStation,
                            remotePlay = remotePlay,
                            remoteStop = remoteStop,
                            localToggle = toggle,
                            localStop = localStop,
                        )
                        Spacer(Modifier.height(6.dp))
                        StationTable(
                            modifier = Modifier.weight(1f),
                            stations = state.stations,
                            currentStationId = currentStationId,
                            openStation = { station -> openStation(station.id) },
                            favourites = personal.favourites.map { it.stationId }.toSet(),
                            toggleFavourite = toggleFavourite,
                        )
                    }
                }
                if (favouritesOpen && state is StationsUiState.Content) {
                    PersonalFavouritesDialog(
                        data = personal,
                        stations = state.catalogue.stations,
                        onDismiss = { favouritesOpen = false },
                        onPlay = { station -> play(station, state.stations) },
                    )
                }
                if (historyOpen && state is StationsUiState.Content) {
                    PersonalHistoryDialog(
                        data = personal,
                        stations = state.catalogue.stations,
                        onDismiss = { historyOpen = false },
                        onPlay = { station -> play(station, state.stations) },
                        onClearHistory = clearHistory,
                    )
                }
            }
            snackbarHostState?.let {
                SnackbarHost(
                    hostState = it,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }
}
