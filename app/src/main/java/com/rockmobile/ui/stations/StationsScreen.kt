package com.rockmobile.ui.stations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState

@Composable
fun StationsScreen(state: StationsUiState, playback: PlaybackState, retry: () -> Unit, play: (Station) -> Unit, toggle: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rockmobile", style = MaterialTheme.typography.headlineMedium)
        when (state) {
            StationsUiState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            is StationsUiState.Error -> { Text(state.message); Button(onClick = retry) { Text("Retry Rockserver") } }
            is StationsUiState.Content -> {
                state.fallbackReason?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.catalogue.source.name.lowercase().replaceFirstChar(Char::uppercase)} catalogue")
                    Button(onClick = retry) { Text("Retry Rockserver") }
                }
                MiniPlayer(playback, toggle)
                LazyColumn { items(state.catalogue.stations, key = { it.id }) { StationRow(it, playback.station?.id == it.id, play) } }
            }
        }
    }
}

@Composable private fun MiniPlayer(state: PlaybackState, toggle: () -> Unit) {
    state.station ?: return
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(state.station.name); Text(state.error ?: if (state.isPlaying) "Playing" else "Paused") }
            Button(onClick = toggle) { Text(if (state.isPlaying) "Pause" else "Play") }
        }
    }
}

@Composable private fun StationRow(station: Station, current: Boolean, play: (Station) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { play(station) }.padding(vertical = 12.dp)) {
        Text(if (current) "▶ ${station.name}" else station.name, style = MaterialTheme.typography.titleMedium)
        val metadata = listOfNotNull(station.tags.takeIf { it.isNotEmpty() }?.joinToString(), station.country, station.codec, station.bitrateKbps?.let { "$it kbps" }).joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}
