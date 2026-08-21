package com.rockmobile.ui.stations

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun StationsScreen(state: StationsUiState, playback: PlaybackState, retry: () -> Unit, updateFilters: ((StationFilters) -> StationFilters) -> Unit, play: (Station, List<Station>) -> Unit, toggle: () -> Unit, openPlayer: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rockmobile", style = MaterialTheme.typography.headlineMedium)
        when (state) {
            StationsUiState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            is StationsUiState.Error -> { Text(state.message); Button(onClick = retry) { Text("Retry") } }
            is StationsUiState.Content -> {
                state.fallbackReason?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.catalogue.source.name.lowercase().replaceFirstChar(Char::uppercase)} catalogue")
                    Button(onClick = retry) { Text("Refresh") }
                }
                SearchAndFilters(state, updateFilters)
                MiniPlayer(playback, toggle, openPlayer)
                if (state.stations.isEmpty()) Text("No stations match these filters.", modifier = Modifier.padding(vertical = 32.dp))
                else LazyColumn { items(state.stations, key = { it.id }) { station -> StationRow(station, playback.station?.id == station.id) { play(station, state.stations) } } }
            }
        }
    }
}

@Composable private fun SearchAndFilters(content: StationsUiState.Content, update: ((StationFilters) -> StationFilters) -> Unit) {
    OutlinedTextField(content.filters.query, { value -> update { it.copy(query = value) } }, label = { Text("Search stations") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterMenu("Genre", content.catalogue.stations.flatMap { it.tags }.distinctSorted(), content.filters.genre) { update { filters -> filters.copy(genre = it) } }
        FilterMenu("Country", content.catalogue.stations.mapNotNull { it.country }.distinctSorted(), content.filters.country) { update { filters -> filters.copy(country = it) } }
        FilterMenu("Language", content.catalogue.stations.mapNotNull { it.language }.distinctSorted(), content.filters.language) { update { filters -> filters.copy(language = it) } }
    }
}

private fun List<String>.distinctSorted() = distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

@Composable private fun FilterMenu(label: String, values: List<String>, selected: String?, select: (String?) -> Unit) {
    if (values.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected != null, onClick = { expanded = true }, label = { Text(selected ?: label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (selected != null) DropdownMenuItem(text = { Text("All $label") }, onClick = { select(null); expanded = false })
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { select(value); expanded = false }) }
        }
    }
}

@Composable private fun MiniPlayer(state: PlaybackState, toggle: () -> Unit, openPlayer: () -> Unit) {
    state.station ?: return
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = openPlayer)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(state.station, Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(state.station.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(state.error ?: state.streamTitle ?: if (state.isPlaying) "Playing" else "Paused", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = toggle) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play") }
        }
    }
}

@Composable private fun StationRow(station: Station, current: Boolean, play: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = play).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StationLogo(station, Modifier.size(48.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (current) "▶ ${station.name}" else station.name, style = MaterialTheme.typography.titleMedium)
            val metadata = listOfNotNull(station.tags.takeIf { it.isNotEmpty() }?.joinToString(), station.country, station.language, station.codec, station.bitrateKbps?.let { "$it kbps" }).joinToString(" · ")
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider()
}

@Composable
fun PlayerScreen(state: PlaybackState, back: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit, retry: () -> Unit) {
    val station = state.station
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Now playing", style = MaterialTheme.typography.headlineSmall) }
        Spacer(Modifier.height(48.dp))
        if (station != null) {
            StationLogo(station, Modifier.size(220.dp)); Spacer(Modifier.height(28.dp))
            Text(station.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            state.streamTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
            state.streamArtist?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
            if (state.streamTitle == null && state.streamArtist == null) Text("Stream metadata is not available", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            state.error?.let { Text("Playback error: $it", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp)); Button(onClick = retry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") } }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = previous, enabled = state.canSkipPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = toggle, modifier = Modifier.size(72.dp)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", modifier = Modifier.size(48.dp)) }
                IconButton(onClick = next, enabled = state.canSkipNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next") }
            }
        } else Text("Choose a station to start listening.", modifier = Modifier.padding(top = 80.dp))
    }
}

/** Network artwork is optional: failed or absent favicons always leave a stable initial placeholder. */
@Composable private fun StationLogo(station: Station, modifier: Modifier = Modifier) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, station.faviconUrl) {
        value = station.faviconUrl?.let { url -> withContext(Dispatchers.IO) { runCatching { URL(url).openConnection().apply { connectTimeout = 4_000; readTimeout = 4_000 }.getInputStream().use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull() } }
    }
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = "${station.name} logo", modifier = Modifier.fillMaxSize())
        else Text(station.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
