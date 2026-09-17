package com.rockmobile.ui.stations

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rockmobile.devicecontrol.CommandLifecycle
import com.rockmobile.devicecontrol.ControlCapability
import com.rockmobile.devicecontrol.LivePlaybackState
import com.rockmobile.devicecontrol.LivePlaybackStatusUi
import com.rockmobile.devicecontrol.PlaybackAction
import com.rockmobile.devicecontrol.TargetDirectoryState
import com.rockmobile.devicecontrol.TargetFreshness
import com.rockmobile.devicecontrol.TargetPresence
import com.rockmobile.devicecontrol.checkDevicePlaySupport
import com.rockmobile.devicecontrol.presentTarget
import com.rockmobile.devicecontrol.stationDisplayTitle
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState

/**
 * Station-first player (ТЗ §5.2): hero card for one catalog station, an output-device
 * selector, a live on-air indicator without any seek/timeline, capability-driven transport
 * and the selected target's volume card with drag isolation. Playback facts come only from
 * the shared live-playback state, never from a tap or a terminal command result.
 */
@Composable
fun StationPlayerScreen(
    stationId: String,
    stations: List<Station>,
    favourite: Boolean,
    back: () -> Unit,
    toggleFavourite: () -> Unit,
    phoneOutput: Boolean,
    selectPhoneOutput: () -> Unit,
    selectTargetOutput: (String) -> Unit,
    directory: TargetDirectoryState,
    live: LivePlaybackState,
    localPlayback: PlaybackState,
    commands: Map<String, CommandLifecycle> = emptyMap(),
    playRemote: (String) -> Unit,
    stopRemote: () -> Unit,
    pauseRemote: () -> Unit,
    setMuteRemote: (Boolean) -> Unit,
    playLocal: (Station, List<Station>) -> Unit,
    toggleLocal: () -> Unit,
    stopLocal: () -> Unit,
    previousLocal: () -> Unit,
    nextLocal: () -> Unit,
    onVolumeDragStart: () -> Unit,
    onVolumeDragChange: (Int) -> Unit,
    onVolumeDragFinish: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val station = stations.singleOrNull { it.id == stationId }
    val stationTitle = stationDisplayTitle(stationId, stations) ?: "Станция"
    val index = stations.indexOfFirst { it.id == stationId }.takeIf { it >= 0 }
    val previousStation = index?.let { if (it > 0) stations[it - 1] else null }
    val nextStation = index?.let { if (it < stations.lastIndex) stations[it + 1] else null }
    val available = directory as? TargetDirectoryState.Available
    val selectedTarget = available?.selectedTarget
    val deviceSupport = checkDevicePlaySupport(directory)
    val presentation = live.presentTarget(selectedTarget?.id)
    var selectorOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад в каталог", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(stationTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = toggleFavourite) { Text(if (favourite) "★" else "☆", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge) }
                }
                Spacer(Modifier.height(18.dp))
                RockPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (station != null) StationLogo(station, Modifier.size(180.dp))
                        else Box(Modifier.size(180.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Text("♪", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(stationTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        val meta = listOfNotNull(
                            station?.tags?.firstOrNull(),
                            listOfNotNull(station?.bitrateKbps?.let { "$it kbit/s" }, station?.codec?.uppercase()).joinToString(" ").ifBlank { null },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        OutputSelectorBadge(
                            phoneOutput = phoneOutput,
                            targetName = selectedTarget?.name,
                            targetOnline = selectedTarget?.presence == TargetPresence.Online,
                            openSelector = { selectorOpen = true },
                        )
                        if (phoneOutput) {
                            LocalOnAirIndicator(localPlayback)
                            LocalTransport(
                                canSkipPrevious = localPlayback.canSkipPrevious,
                                canSkipNext = localPlayback.canSkipNext,
                                isPlaying = localPlayback.isPlaying,
                                toggle = toggleLocal,
                                stop = stopLocal,
                                previous = previousLocal,
                                next = nextLocal,
                            )
                            localPlayback.error?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                        } else {
                            val blockingReason = if (deviceSupport.supported) null else deviceSupport.reason
                            RemoteOnAirIndicator(presentation)
                            if (blockingReason != null) {
                                Text(
                                    blockingReason,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            presentation?.failure?.let { failure ->
                                Text(
                                    failure.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            RemoteTransport(
                                stationMissing = station == null,
                                support = deviceSupport.supported,
                                playbackActions = selectedTarget?.capability<ControlCapability.Playback>()?.actions ?: emptySet(),
                                awaitingConfirmation = presentation?.status == LivePlaybackStatusUi.AwaitingConfirmation,
                                play = { station?.let { playRemote(it.id) } },
                                stop = stopRemote,
                                pause = pauseRemote,
                                previousAvailable = previousStation != null,
                                nextAvailable = nextStation != null,
                                previous = { previousStation?.let { playRemote(it.id) } },
                                next = { nextStation?.let { playRemote(it.id) } },
                            )
                            RemoteVolumeCard(
                                targetId = selectedTarget?.id,
                                volumeCapability = selectedTarget?.capability<ControlCapability.Volume>(),
                                presentation = presentation,
                                volumeCommandInFlight = commands.values.any {
                                    it.targetId == selectedTarget?.id && it.actionKey.startsWith("volume.set_volume:") && it.inFlight
                                },
                                setMuteRemote = setMuteRemote,
                                onDragStart = onVolumeDragStart,
                                onDragChange = onVolumeDragChange,
                                onDragFinish = onVolumeDragFinish,
                            )
                        }
                    }
                }
            }
            snackbarHostState?.let {
                SnackbarHost(hostState = it, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        }
    }
    if (selectorOpen) {
        OutputSelectorDialog(
            directory = directory,
            phoneOutput = phoneOutput,
            dismiss = { selectorOpen = false },
            selectPhone = { selectPhoneOutput(); selectorOpen = false },
            selectTarget = { selectTargetOutput(it); selectorOpen = false },
        )
    }
}

/** Interactive `Играть на: [ RockCast · В сети ▼ ]` badge from the Station-First mockups. */
@Composable
private fun OutputSelectorBadge(phoneOutput: Boolean, targetName: String?, targetOnline: Boolean, openSelector: () -> Unit) {
    Surface(
        onClick = openSelector,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Играть на:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Icon(
                if (phoneOutput) Icons.Default.PhoneAndroid else Icons.Default.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            val label = when {
                phoneOutput -> "Этот телефон"
                targetName != null -> targetName + if (targetOnline) " · В сети" else " · Нет связи"
                else -> "Устройство не выбрано"
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Выбрать устройство вывода", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Live-radio status line; deliberately no timeline, seek bar or elapsed counters. */
@Composable
private fun RemoteOnAirIndicator(presentation: com.rockmobile.devicecontrol.LiveTargetPresentation?) {
    val status = presentation?.status
    val (label, color) = when (status) {
        LivePlaybackStatusUi.Playing -> "● В ЭФИРЕ · Воспроизводится" to MaterialTheme.colorScheme.primary
        LivePlaybackStatusUi.Buffering -> "● В ЭФИРЕ · Буферизация…" to MaterialTheme.colorScheme.secondary
        LivePlaybackStatusUi.AwaitingConfirmation -> "Ожидаем подтверждения…" to MaterialTheme.colorScheme.onSurfaceVariant
        LivePlaybackStatusUi.Stopped -> "Остановлено" to MaterialTheme.colorScheme.onSurfaceVariant
        LivePlaybackStatusUi.Error -> "Ошибка воспроизведения" to MaterialTheme.colorScheme.error
        LivePlaybackStatusUi.Paused -> "Приостановлено" to MaterialTheme.colorScheme.onSurfaceVariant
        LivePlaybackStatusUi.Idle -> "Не воспроизводится" to MaterialTheme.colorScheme.onSurfaceVariant
        LivePlaybackStatusUi.Unknown, null -> "Состояние устройства неизвестно" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulse by rememberInfiniteTransition(label = "onair").animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "onair-dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
        if (status == LivePlaybackStatusUi.Playing) {
            Text("●", color = color.copy(alpha = pulse), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LocalOnAirIndicator(state: PlaybackState) {
    val (label, color) = when {
        state.isPlaying -> "● В ЭФИРЕ · Воспроизводится" to MaterialTheme.colorScheme.primary
        state.error != null -> "Ошибка воспроизведения" to MaterialTheme.colorScheme.error
        state.station != null -> "Приостановлено" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Нажмите Play, чтобы слушать на телефоне" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
}

/** Capability-driven transport; Pause exists only for targets advertising the `pause` action. */
@Composable
private fun RemoteTransport(
    stationMissing: Boolean,
    support: Boolean,
    playbackActions: Set<PlaybackAction>,
    awaitingConfirmation: Boolean,
    play: () -> Unit,
    stop: () -> Unit,
    pause: () -> Unit,
    previousAvailable: Boolean,
    nextAvailable: Boolean,
    previous: () -> Unit,
    next: () -> Unit,
) {
    val playEnabled = support && !stationMissing && !awaitingConfirmation
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
        IconButton(
            onClick = previous,
            enabled = PlaybackAction.Previous in playbackActions && previousAvailable,
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Default.SkipPrevious, "Предыдущая станция", tint = if (PlaybackAction.Previous in playbackActions && previousAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
        if (awaitingConfirmation) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Button(
                onClick = play,
                enabled = playEnabled,
                modifier = Modifier.size(68.dp).clip(CircleShape),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Default.PlayArrow, "Играть", modifier = Modifier.size(36.dp)) }
        }
        if (PlaybackAction.Pause in playbackActions) {
            IconButton(onClick = pause, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Pause, "Пауза", tint = MaterialTheme.colorScheme.onSurface) }
        }
        IconButton(
            onClick = stop,
            enabled = PlaybackAction.Stop in playbackActions && support,
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Default.Stop, "Остановить", tint = if (PlaybackAction.Stop in playbackActions && support) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
        IconButton(
            onClick = next,
            enabled = PlaybackAction.Next in playbackActions && nextAvailable,
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Default.SkipNext, "Следующая станция", tint = if (PlaybackAction.Next in playbackActions && nextAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
    }
}

@Composable
private fun LocalTransport(
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isPlaying: Boolean,
    toggle: () -> Unit,
    stop: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
        IconButton(onClick = previous, enabled = canSkipPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "Предыдущая станция", tint = if (canSkipPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
        IconButton(onClick = toggle, modifier = Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Пауза" else "Играть", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = stop, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Stop, "Остановить", tint = MaterialTheme.colorScheme.onSurface) }
        IconButton(onClick = next, enabled = canSkipNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "Следующая станция", tint = if (canSkipNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
    }
}

/**
 * Volume card of the selected remote target. The slider value comes from the shared live
 * presentation: while a drag is active the reducer keeps network echoes away from the thumb,
 * and exactly one `volume.set_volume` is committed on release with «Применяем…» until echo.
 */
@Composable
private fun RemoteVolumeCard(
    targetId: String?,
    volumeCapability: ControlCapability.Volume?,
    presentation: com.rockmobile.devicecontrol.LiveTargetPresentation?,
    volumeCommandInFlight: Boolean,
    setMuteRemote: (Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDragChange: (Int) -> Unit,
    onDragFinish: () -> Unit,
) {
    if (targetId == null || volumeCapability == null || presentation == null) return
    if (presentation.status == LivePlaybackStatusUi.Unknown && presentation.volumePercent == null) {
        Text("Громкость устройства неизвестна", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val level = presentation.volumePercent ?: 0
    Spacer(Modifier.height(14.dp))
    RockPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Громкость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (volumeCapability.mute) {
                val muted = presentation.muted == true
                IconButton(onClick = { setMuteRemote(!muted) }, enabled = !volumeCommandInFlight) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Включить звук" else "Выключить звук",
                        tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val sliderSteps = if (volumeCapability.step > 1) (volumeCapability.maximum - volumeCapability.minimum) / volumeCapability.step - 1 else 0
        Slider(
            value = level.toFloat().coerceIn(volumeCapability.minimum.toFloat(), volumeCapability.maximum.toFloat()),
            onValueChange = { value ->
                // Slider has no drag-start callback; the first change starts the gesture so
                // the reducer can isolate the thumb from network echoes until release.
                if (!presentation.volumeDragging) onDragStart()
                onDragChange(value.toInt())
            },
            onValueChangeFinished = onDragFinish,
            valueRange = volumeCapability.minimum.toFloat()..volumeCapability.maximum.toFloat(),
            steps = sliderSteps,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$level%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (presentation.volumeApplying) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Применяем…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            } else if (presentation.muted == true) {
                Text("Звук выключен", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Output chooser with online/offline and fresh/stale indicators; unusable targets explain why. */
@Composable
private fun OutputSelectorDialog(
    directory: TargetDirectoryState,
    phoneOutput: Boolean,
    dismiss: () -> Unit,
    selectPhone: () -> Unit,
    selectTarget: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Закрыть") } },
        title = { Text("Куда играть?") },
        text = {
            Column {
                OutlinedButton(onClick = selectPhone, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(20.dp), tint = if (phoneOutput) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(if (phoneOutput) "✓ Этот телефон" else "Этот телефон", modifier = Modifier.weight(1f))
                }
                val available = directory as? TargetDirectoryState.Available
                if (available == null) {
                    Text("Устройства появятся после подключения Rock-аккаунта.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                } else {
                    available.targets.forEach { target ->
                        val online = target.presence == TargetPresence.Online
                        val status = listOfNotNull(
                            if (online) "В сети" else "Нет связи",
                            when (target.freshness) {
                                TargetFreshness.Stale -> "данные устарели"
                                TargetFreshness.Unknown -> "состояние неизвестно"
                                TargetFreshness.Fresh -> null
                            },
                        ).joinToString(" · ")
                        val enabled = target.usable
                        OutlinedButton(
                            onClick = { selectTarget(target.id) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Icon(
                                if (online) Icons.Default.Speaker else Icons.Default.Stop,
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (available.selectedTargetId == target.id) "✓ ${target.name}" else target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (enabled) status else "$status · управление недоступно", style = MaterialTheme.typography.labelSmall, color = if (online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
    )
}
