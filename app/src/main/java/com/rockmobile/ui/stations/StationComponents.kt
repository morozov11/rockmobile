package com.rockmobile.ui.stations

import com.rockmobile.BuildConfig
import com.rockmobile.settings.resolvedRockserverUrl

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rockmobile.R
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.data.stations.StationIconLoader
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState
import com.rockmobile.voice.VoiceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalSummary(
    data: PersonalData,
    onOpenFavourites: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    if (data.favourites.isEmpty() && data.history.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (data.favourites.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = onOpenFavourites,
                label = { Text("Favourites ${data.favourites.size}") },
            )
        }
        if (data.history.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = onOpenHistory,
                label = { Text("History ${data.history.size}") },
            )
        }
    }
}

@Composable
internal fun VoiceStatusBar(state: VoiceUiState, cancel: () -> Unit, dismiss: () -> Unit) {
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
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
internal fun RockHeader(retry: () -> Unit, openAccount: () -> Unit, openDevices: () -> Unit, accountConnected: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(R.mipmap.rockmobile_icon), contentDescription = rockMobileLogoDescription(), modifier = Modifier.size(30.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.width(8.dp))
            Text(rockMobileTitle(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Row {
            IconButton(onClick = openDevices) {
                Icon(
                    Icons.Default.Devices,
                    "Устройства",
                    tint = if (accountConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = openAccount) {
                Icon(
                    Icons.Default.Person,
                    if (accountConnected) "Rock-аккаунт подключён" else "Подключить Rock-аккаунт",
                    tint = if (accountConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = retry) { Icon(Icons.Default.Refresh, "Refresh catalogue", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

internal fun rockMobileTitle() = "RockMobile"

internal fun rockMobileLogoDescription() = "RockMobile logo"

@Composable
internal fun LoadingState() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Loading stations…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ErrorState(message: String, retry: () -> Unit) {
    RockPanel(Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("Catalogue unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = retry, modifier = Modifier.padding(top = 18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Try again") }
    }
}

@Composable
internal fun FallbackBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun CatalogueHeader(source: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Stations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("  · ${source.lowercase().replaceFirstChar(Char::uppercase)} · $count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SearchAndFilters(
    content: StationsUiState.Content,
    voice: VoiceUiState,
    update: ((StationFilters) -> StationFilters) -> Unit,
    search: () -> Unit,
    startVoice: () -> Unit,
    finishVoice: () -> Unit,
    cancelVoice: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val pulseTransition = rememberInfiniteTransition(label = "voice microphone pulse")
    val pulseAlpha by pulseTransition.animateFloat(initialValue = .45f, targetValue = .95f, animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "voice microphone pulse alpha")
    OutlinedTextField(
        value = content.filters.query,
        onValueChange = { value -> update { it.copy(query = value) } },
        placeholder = { Text("Find a station") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            when (voice) {
                VoiceUiState.Recording -> IconButton(onClick = finishVoice, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha))) { Icon(Icons.Default.Stop, "Finish voice recording and search now", tint = MaterialTheme.colorScheme.onError) }
                is VoiceUiState.Processing -> IconButton(onClick = cancelVoice) { Icon(Icons.Default.Close, "Cancel voice command") }
                else -> if (content.filters.query.isNotBlank()) {
                    Row {
                        IconButton(onClick = { update { it.copy(query = "") } }) {
                            Icon(Icons.Default.Close, "Clear search text", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { keyboard?.hide(); search() }, enabled = !content.searching) {
                            if (content.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Search, "Search typed text", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    IconButton(onClick = startVoice) { Icon(Icons.Default.Mic, "Start voice search", tint = MaterialTheme.colorScheme.primary) }
                }
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
    val filterOptions = content.filterOptions
    val genres = filterOptions?.genres ?: content.catalogue.stations.flatMap { it.tags }.distinctSorted()
    val countries = filterOptions?.countries ?: content.catalogue.stations.mapNotNull { it.country }.distinctSorted()
    val languages = filterOptions?.languages ?: content.catalogue.stations.mapNotNull { it.language }.distinctSorted()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterMenu("Genre", genres, content.filters.genre) { update { filters -> filters.copy(genre = it) } }
        FilterMenu("Country", countries, content.filters.country) { update { filters -> filters.copy(country = it) } }
        FilterMenu("Language", languages, content.filters.language) { update { filters -> filters.copy(language = it) } }
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
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, labelColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected != null, borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (selected != null) DropdownMenuItem(text = { Text("All $label") }, onClick = { select(null); expanded = false })
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { select(value); expanded = false }) }
        }
    }
}

/** Catalogue list focused on music (ТЗ §5.1): rows carry no per-device playback buttons. */
@Composable
internal fun StationTable(
    modifier: Modifier = Modifier,
    stations: List<Station>,
    currentStationId: String?,
    isPlaying: Boolean = false,
    play: (Station) -> Unit,
    pause: () -> Unit = {},
    openStation: (Station) -> Unit,
    favourites: Set<String>,
    toggleFavourite: (Station) -> Unit,
    selectGenre: ((String) -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
        if (stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stations match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp)) {
                itemsIndexed(stations, key = { _, station -> station.id }) { _, station ->
                    val isCurrent = currentStationId == station.id
                    StationRow(
                        station = station,
                        current = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        favourite = station.id in favourites,
                        play = { play(station) },
                        pause = pause,
                        openStation = { openStation(station) },
                        toggleFavourite = { toggleFavourite(station) },
                        onGenreClick = selectGenre,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RockPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

/**
 * Sticky mini-player (ТЗ §5.1): visible only while something confirmed is playing on a
 * remote target or on the phone itself. It shows Play/Stop actions and never a horizontal
 * volume slider; a tap opens the station screen of the currently confirmed station.
 */
@Composable
internal fun LiveMiniPlayer(
    remote: com.rockmobile.devicecontrol.LiveTargetPresentation?,
    remoteTargetName: String?,
    local: PlaybackState,
    stations: List<Station>,
    openStation: (String) -> Unit,
    remotePlay: (String) -> Unit,
    remoteStop: () -> Unit,
    localToggle: () -> Unit,
    localStop: () -> Unit,
    phoneOutput: Boolean = false,
) {
    val remoteActive = !phoneOutput && remote != null && (remote.status == com.rockmobile.devicecontrol.LivePlaybackStatusUi.Playing || remote.status == com.rockmobile.devicecontrol.LivePlaybackStatusUi.Buffering)
    val localActive = local.station != null && local.isPlaying
    if (!remoteActive && !localActive) return
    val remoteStationId = remote?.confirmedStationId
    val remoteTitle = com.rockmobile.devicecontrol.stationDisplayTitle(remoteStationId, stations)
    val remoteStation = remoteStationId?.let { id -> stations.singleOrNull { it.id == id } }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable(onClick = {
            when {
                localActive -> local.station?.let { openStation(it.id) }
                remoteActive && remoteStationId != null -> openStation(remoteStationId)
                else -> Unit
            }
        }),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (localActive) {
                val station = local.station ?: return@Row
                StationLogo(station, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        local.error ?: local.streamTitle ?: "Этот телефон",
                        color = if (local.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = localToggle, modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
                    Icon(if (local.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (local.isPlaying) "Пауза" else "Играть", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = localStop, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Stop, "Остановить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            } else if (remoteActive) {
                StationLogoOrPlaceholder(remoteTitle, remoteStation, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(remoteTitle ?: "Станция", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    remote?.trackTitle?.let { title ->
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    val percent = remote?.volumePercent
                    Text(
                        listOfNotNull(remoteTargetName, percent?.let { "$it%" }).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { remoteStationId?.let(remotePlay) },
                    enabled = remoteStationId != null,
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.Default.PlayArrow, "Играть", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
                IconButton(onClick = remoteStop, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Stop, "Остановить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            }
            Icon(Icons.Default.ChevronRight, "Открыть экран станции", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Remote stations may be missing from the local catalogue; the tile then degrades to a letter. */
@Composable
private fun StationLogoOrPlaceholder(title: String?, station: Station?, modifier: Modifier = Modifier) {
    if (station != null) StationLogo(station, modifier)
    else Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(title?.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Variant-A row: the station name owns the full remaining width, tags are chips on a second
 * line, country/bitrate/codec collapse into one quiet suffix, and the only action is a round
 * Play/Pause. Favourite lives as a badge on the logo corner; a long press opens station details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(
    station: Station,
    current: Boolean,
    isPlaying: Boolean,
    favourite: Boolean,
    play: () -> Unit,
    pause: () -> Unit,
    openStation: () -> Unit,
    toggleFavourite: () -> Unit,
    onGenreClick: ((String) -> Unit)? = null,
) {
    val activeAndPlaying = current && isPlaying
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = if (activeAndPlaying) pause else play,
                onLongClick = openStation,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) {
            StationLogo(station, Modifier.size(48.dp).align(Alignment.Center))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = toggleFavourite),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (favourite) "★" else "☆",
                    color = if (favourite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    station.name,
                    color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (activeAndPlaying) StationEqualizer(MaterialTheme.colorScheme.secondary)
            }
            Row(
                Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                station.tags.take(3).forEach { tag ->
                    StationTagChip(tag, current = current, onClick = onGenreClick?.let { select -> { select(tag) } })
                }
                if (station.tags.size > 3) StationTagChip("+${station.tags.size - 3}", muted = true, current = current)
                val meta = listOfNotNull(station.country, station.bitrateKbps?.let { "${it}k" }, station.codec?.uppercase()).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        color = if (current) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        IconButton(
            onClick = if (activeAndPlaying) pause else play,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(if (activeAndPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (activeAndPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (activeAndPlaying) "Приостановить станцию ${station.name}" else "Включить станцию ${station.name}",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
}

/** Pill tag chip; a click applies the catalogue genre filter when a handler is provided. */
@Composable
private fun StationTagChip(label: String, muted: Boolean = false, current: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val container = if (current) MaterialTheme.colorScheme.background.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (current) BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .25f)) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val content = when {
        muted -> MaterialTheme.colorScheme.onSurfaceVariant
        current -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        modifier = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier,
        shape = shape,
        color = container,
        border = border,
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Three-bar equalizer marking the row that is actually sounding right now. */
@Composable
private fun StationEqualizer(tint: Color) {
    val transition = rememberInfiniteTransition(label = "station equalizer")
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { bar ->
            val level by transition.animateFloat(
                initialValue = if (bar == 1) .85f else .3f,
                targetValue = if (bar == 1) .3f else .95f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 320 + bar * 60, delayMillis = bar * 80), RepeatMode.Reverse),
                label = "station equalizer bar $bar",
            )
            Box(Modifier.fillMaxHeight(level).width(3.dp).clip(CircleShape).background(tint))
        }
    }
}

/** Network artwork is optional; failed favicons leave a stable RockCast tile. */
@Composable
internal fun StationLogo(station: Station, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val serverBase = resolvedRockserverUrl(BuildConfig.DEBUG, BuildConfig.DEBUG_ROCKSERVER_URL)
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, station.id, station.faviconUrl, station.homepageUrl) {
        value = withContext(Dispatchers.IO) { StationIconLoader.loadOrFetch(context.applicationContext, station, serverBase)?.asImageBitmap() }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = "${station.name} logo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(station.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
