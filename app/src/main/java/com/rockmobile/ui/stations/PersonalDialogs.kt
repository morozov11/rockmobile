package com.rockmobile.ui.stations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.data.personal.UnresolvedReference
import com.rockmobile.domain.model.Station
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PersonalFavouritesDialog(
    data: PersonalData,
    stations: List<Station>,
    onDismiss: () -> Unit,
    onPlay: (Station) -> Unit,
) {
    val byId = stations.associateBy { it.id }
    val favourites = data.favourites.mapNotNull { byId[it.stationId] }
    val unavailable = data.unresolved.filter { it.sourceKind == "favourite" }
    PersonalStationListDialog(
        title = "Favourites",
        emptyMessage = "No favourite stations yet. Tap ☆ in the station list.",
        stations = favourites,
        unavailable = unavailable,
        onDismiss = onDismiss,
        onPlay = onPlay,
    )
}

@Composable
internal fun PersonalHistoryDialog(
    data: PersonalData,
    stations: List<Station>,
    onDismiss: () -> Unit,
    onPlay: (Station) -> Unit,
    onClearHistory: () -> Unit,
) {
    val byId = stations.associateBy { it.id }
    val historyStations = data.history.mapNotNull { entry -> byId[entry.stationId]?.let { entry to it } }
    val unavailable = data.unresolved.filter { it.sourceKind == "history" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Stored only on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (historyStations.isEmpty() && unavailable.isEmpty()) {
                    Text("No playback history yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                historyStations.forEach { (entry, station) ->
                    PersonalStationRow(
                        title = station.name,
                        subtitle = formatPersonalTimestamp(entry.lastPlayedAt),
                        onClick = { onPlay(station); onDismiss() },
                    )
                }
                unavailable.forEach { entry -> UnavailablePersonalRow(entry) }
            }
        },
        confirmButton = {
            if (data.history.isNotEmpty()) {
                TextButton(onClick = { onClearHistory(); onDismiss() }) { Text("Clear history") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PersonalStationListDialog(
    title: String,
    emptyMessage: String,
    stations: List<Station>,
    unavailable: List<UnresolvedReference>,
    onDismiss: () -> Unit,
    onPlay: (Station) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Stored only on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stations.isEmpty() && unavailable.isEmpty()) {
                    Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                stations.forEach { station ->
                    PersonalStationRow(
                        title = station.name,
                        subtitle = station.tags.joinToString(", ").takeIf { it.isNotBlank() },
                        onClick = { onPlay(station); onDismiss() },
                    )
                }
                unavailable.forEach { entry -> UnavailablePersonalRow(entry) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PersonalStationRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnavailablePersonalRow(entry: UnresolvedReference) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            entry.lastKnownName ?: entry.originalStationId,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Station unavailable · ${entry.originalStationId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatPersonalTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)
