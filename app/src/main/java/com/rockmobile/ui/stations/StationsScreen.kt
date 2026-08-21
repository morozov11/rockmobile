package com.rockmobile.ui.stations

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rockmobile.domain.model.Station
import com.rockmobile.R
import com.rockmobile.playback.PlaybackState
import com.rockmobile.voice.VoiceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

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
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            RockHeader(retry)
            Spacer(Modifier.height(6.dp))
            when (state) {
                StationsUiState.Loading -> LoadingState()
                is StationsUiState.Error -> ErrorState(state.message, retry)
                is StationsUiState.Content -> {
                    state.fallbackReason?.let { FallbackBanner(it) }
                    CatalogueHeader(state.catalogue.source.name, state.stations.size)
                    SearchAndFilters(state, voice, updateFilters, onVoice, onFinishVoice, onCancelVoice)
                    VoiceStatusBar(voice, onCancelVoice, onDismissVoice)
                    MiniPlayer(playback, toggle, openPlayer)
                    Spacer(Modifier.height(6.dp))
                    StationTable(
                        modifier = Modifier.weight(1f),
                        stations = state.stations,
                        currentStationId = playback.station?.id,
                        play = { station -> play(station, state.stations) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceStatusBar(state: VoiceUiState, cancel: () -> Unit, dismiss: () -> Unit) {
    if (state == VoiceUiState.Idle) return
    val (message, busy) = when (state) {
        VoiceUiState.Recording -> "Listening… searching after you finish speaking" to false
        is VoiceUiState.Processing -> (state.transcript?.let { "Processing “$it”" } ?: "Processing voice command…") to true
        VoiceUiState.PermissionRequired -> "Microphone permission is required" to false
        VoiceUiState.PermissionDenied -> "Microphone permission denied — tap the mic to retry" to false
        VoiceUiState.PermissionPermanentlyDenied -> "Enable microphone permission in Android settings" to false
        is VoiceUiState.Success -> "Voice: “${state.transcript}” · ${state.stationName}" to false
        is VoiceUiState.NoMatch -> "No stations found for “${state.transcript}”" to false
        is VoiceUiState.NoPlayableStation -> "The matched stations are currently unavailable" to false
        VoiceUiState.ServerUnavailable -> "Voice service unavailable; radio still works" to false
        is VoiceUiState.RecoverableError -> state.message to false
        VoiceUiState.Idle -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(message, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = if (busy) 8.dp else 0.dp))
            IconButton(onClick = if (state is VoiceUiState.Recording || state is VoiceUiState.Processing) cancel else dismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, if (state is VoiceUiState.Recording || state is VoiceUiState.Processing) "Cancel voice command" else "Dismiss voice message", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RockHeader(retry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.rockmobile_icon),
                contentDescription = "RockCast logo",
                modifier = Modifier.size(30.dp).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "RockCast",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(onClick = retry) {
            Icon(Icons.Default.Refresh, "Refresh catalogue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Loading stations…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    RockPanel(Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("Catalogue unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Button(
            onClick = retry,
            modifier = Modifier.padding(top = 18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("Try again") }
    }
}

@Composable
private fun FallbackBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CatalogueHeader(source: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Stations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("  · ${source.lowercase().replaceFirstChar(Char::uppercase)} · $count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SearchAndFilters(
    content: StationsUiState.Content,
    voice: VoiceUiState,
    update: ((StationFilters) -> StationFilters) -> Unit,
    startVoice: () -> Unit,
    finishVoice: () -> Unit,
    cancelVoice: () -> Unit,
) {
    val pulseTransition = rememberInfiniteTransition(label = "voice microphone pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = .45f,
        targetValue = .95f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "voice microphone pulse alpha",
    )
    OutlinedTextField(
        value = content.filters.query,
        onValueChange = { value -> update { it.copy(query = value) } },
        placeholder = { Text("Find a station") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            when (voice) {
                VoiceUiState.Recording -> IconButton(
                    onClick = finishVoice,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)),
                ) { Icon(Icons.Default.Stop, "Finish voice recording and search now", tint = MaterialTheme.colorScheme.onError) }
                is VoiceUiState.Processing -> IconButton(onClick = cancelVoice) { Icon(Icons.Default.Close, "Cancel voice command") }
                else -> IconButton(onClick = startVoice) { Icon(Icons.Default.Mic, "Start voice search", tint = MaterialTheme.colorScheme.primary) }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterMenu("Genre", content.catalogue.stations.flatMap { it.tags }.distinctSorted(), content.filters.genre) { update { filters -> filters.copy(genre = it) } }
        FilterMenu("Country", content.catalogue.stations.mapNotNull { it.country }.distinctSorted(), content.filters.country) { update { filters -> filters.copy(country = it) } }
        FilterMenu("Language", content.catalogue.stations.mapNotNull { it.language }.distinctSorted(), content.filters.language) { update { filters -> filters.copy(language = it) } }
    }
}

private fun List<String>.distinctSorted() = distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

@Composable
private fun FilterMenu(label: String, values: List<String>, selected: String?, select: (String?) -> Unit) {
    if (values.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(selected ?: label) },
            shape = MaterialTheme.shapes.small,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected != null,
                borderColor = MaterialTheme.colorScheme.outline,
                selectedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (selected != null) DropdownMenuItem(text = { Text("All $label") }, onClick = { select(null); expanded = false })
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { select(value); expanded = false }) }
        }
    }
}

@Composable
private fun StationTable(modifier: Modifier = Modifier, stations: List<Station>, currentStationId: String?, play: (Station) -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
    ) {
        if (stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stations match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp)) {
                itemsIndexed(stations, key = { _, station -> station.id }) { index, station ->
                    StationRow(station, currentStationId == station.id, index) { play(station) }
                }
            }
        }
    }
}

@Composable
private fun RockPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
    ) { Column(Modifier.padding(12.dp), content = content) }
}

@Composable
private fun MiniPlayer(state: PlaybackState, toggle: () -> Unit, openPlayer: () -> Unit) {
    val station = state.station ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable(onClick = openPlayer),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(station, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(state.error ?: state.streamTitle ?: if (state.isPlaying) "Playing" else "Paused", color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = toggle, modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Icon(Icons.Default.ChevronRight, "Open player", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StationRow(station: Station, current: Boolean, index: Int, play: () -> Unit) {
    val rowColor = when {
        current -> MaterialTheme.colorScheme.primary
        index % 2 == 1 -> MaterialTheme.colorScheme.background.copy(alpha = .32f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(rowColor).clickable(onClick = play).padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StationLogo(station, Modifier.size(38.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1.5f)) {
            Text(if (current) "▶ ${station.name}" else station.name, color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            station.country?.let { Text(it, color = if (current) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
        }
        Text(station.tags.joinToString(", "), color = if (current) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
        Text(listOfNotNull(station.bitrateKbps?.let { "$it k" }, station.codec).joinToString(" / ").ifBlank { "—" }, color = if (current) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(.75f))
        IconButton(onClick = play, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.PlayArrow, "Play ${station.name}", tint = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
}

@Composable
fun PlayerScreen(state: PlaybackState, back: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit, retry: () -> Unit) {
    val station = state.station
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Now playing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            if (station != null) {
                RockPanel(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ROCKCAST PLAYER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(28.dp))
                        StationLogo(station, Modifier.size(220.dp))
                        Spacer(Modifier.height(24.dp))
                        Text(station.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        state.streamTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)) }
                        state.streamArtist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                        if (state.streamTitle == null && state.streamArtist == null) Text("Track info appears after Play", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                        state.error?.let {
                            Text("Couldn't connect to this station", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                            Button(onClick = retry, modifier = Modifier.padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Try again") }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            IconButton(onClick = previous, enabled = state.canSkipPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", tint = MaterialTheme.colorScheme.onSurface) }
                            IconButton(onClick = toggle, modifier = Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
                            IconButton(onClick = next, enabled = state.canSkipNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "Next", tint = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Text("Choose a station to start listening.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Network artwork is optional; failed favicons leave a stable RockCast tile. */
@Composable
private fun StationLogo(station: Station, modifier: Modifier = Modifier) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, station.faviconUrl) {
        value = station.faviconUrl?.let { url -> withContext(Dispatchers.IO) { runCatching { URL(url).openConnection().apply { connectTimeout = 4_000; readTimeout = 4_000 }.getInputStream().use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull() } }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = "${station.name} logo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(station.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
