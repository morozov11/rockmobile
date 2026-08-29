package com.rockmobile.ui.stations

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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState
import com.rockmobile.voice.VoiceUiState

@Composable
fun StationsScreen(
    state: StationsUiState,
    playback: PlaybackState,
    voice: VoiceUiState,
    retry: () -> Unit,
    updateFilters: ((StationFilters) -> StationFilters) -> Unit,
    play: (Station, List<Station>) -> Unit,
    toggle: () -> Unit,
    onVoice: () -> Unit,
    onFinishVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onDismissVoice: () -> Unit,
    openPlayer: () -> Unit,
    personal: PersonalData,
    toggleFavourite: (Station) -> Unit,
    openAccount: () -> Unit,
    accountConnected: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            RockHeader(retry, openAccount, accountConnected)
            Spacer(Modifier.height(6.dp))
            when (state) {
                StationsUiState.Loading -> LoadingState()
                is StationsUiState.Error -> ErrorState(state.message, retry)
                is StationsUiState.Content -> {
                    state.fallbackReason?.let { FallbackBanner(it) }
                    CatalogueHeader(state.catalogue.source.name, state.stations.size)
                    PersonalSummary(personal, state.catalogue.stations, play)
                    SearchAndFilters(state, voice, updateFilters, onVoice, onFinishVoice, onCancelVoice)
                    VoiceStatusBar(voice, onCancelVoice, onDismissVoice)
                    MiniPlayer(playback, toggle, openPlayer)
                    Spacer(Modifier.height(6.dp))
                    StationTable(
                        modifier = Modifier.weight(1f),
                        stations = state.stations,
                        currentStationId = playback.station?.id,
                        play = { station -> play(station, state.stations) },
                        favourites = personal.favourites.map { it.stationId }.toSet(),
                        toggleFavourite = toggleFavourite,
                    )
                }
            }
        }
    }
}
