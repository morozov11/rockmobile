package com.rockmobile.devicecontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant

/** Device-only UI: a user selects one directory target before any command is available. */
@Composable
fun DeviceControlScreen(
    state: TargetDirectoryState,
    commands: Map<String, CommandLifecycle>,
    receivers: List<EphemeralReceiver>,
    back: () -> Unit,
    refresh: () -> Unit,
    select: (String) -> Unit,
    dispatch: (RemoteCommand) -> Unit,
    openAccount: () -> Unit,
) {
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    val selectedTarget = (state as? TargetDirectoryState.Available)?.selectedTarget
    if (remoteOpen && state is TargetDirectoryState.Available && selectedTarget != null) {
        RemoteControlScreen(
            directory = state,
            target = selectedTarget,
            commands = commands,
            receivers = receivers,
            back = { remoteOpen = false },
            dispatch = dispatch,
        )
    } else {
        DeviceHubScreen(
            state = state,
            commands = commands,
            back = back,
            refresh = refresh,
            select = { targetId -> select(targetId) },
            openRemote = { remoteOpen = true },
            openAccount = openAccount,
        )
    }
}

@Composable
private fun DeviceHubScreen(
    state: TargetDirectoryState,
    commands: Map<String, CommandLifecycle>,
    back: () -> Unit,
    refresh: () -> Unit,
    select: (String) -> Unit,
    openRemote: () -> Unit,
    openAccount: () -> Unit,
) {
    ScreenFrame(back = back, title = "Устройства", action = refresh) {
        when (state) {
            TargetDirectoryState.Inactive -> EmptyDevices("Подключите Rock-аккаунт, чтобы управлять домашними плеерами.", "Подключить аккаунт", openAccount)
            TargetDirectoryState.Loading -> EmptyDevices("Загружаем доступные устройства…", null, null)
            is TargetDirectoryState.Unavailable -> EmptyDevices(state.message, "Повторить", refresh, error = state.scopeMissing)
            is TargetDirectoryState.Available -> {
                Text("Выберите, где будет звучать радио", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.notice?.let { Notice(it) }
                Spacer(Modifier.height(12.dp))
                if (state.targets.isEmpty()) {
                    EmptyDevices("Нет устройств, доступных для управления.", "Обновить", refresh)
                } else {
                    state.targets.forEach { target ->
                        DeviceCard(
                            target = target,
                            selected = target.id == state.selectedTargetId,
                            commandInFlight = commands.values.any { it.targetId == target.id && it.inFlight },
                            select = { select(target.id) },
                            openRemote = openRemote,
                            canOpenRemote = target.id == state.selectedTargetId && state.mayControlMedia,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (state.requiresSelection) {
                        Text("Команды отправляются только на устройство, выбранное здесь.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    target: ControllerTarget,
    selected: Boolean,
    commandInFlight: Boolean,
    select: () -> Unit,
    openRemote: () -> Unit,
    canOpenRemote: Boolean,
) {
    val online = target.presence == TargetPresence.Online
    val usable = target.usable
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .6f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (online) Icons.Default.Speaker else Icons.Default.WifiOff, null, tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${target.type} · ${deviceStatus(target)}", style = MaterialTheme.typography.bodySmall, color = if (online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                if (selected) Icon(Icons.Default.CheckCircle, "Выбрано", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (selected && canOpenRemote) {
                Button(onClick = openRemote, enabled = !commandInFlight, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (commandInFlight) "Обновляем состояние…" else "Открыть пульт")
                }
            } else {
                OutlinedButton(onClick = select, enabled = usable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected) "Выбрано" else if (usable) "Выбрать устройство" else deviceStatus(target))
                }
            }
        }
    }
}

@Composable
private fun RemoteControlScreen(
    directory: TargetDirectoryState.Available,
    target: ControllerTarget,
    commands: Map<String, CommandLifecycle>,
    receivers: List<EphemeralReceiver>,
    back: () -> Unit,
    dispatch: (RemoteCommand) -> Unit,
) {
    var outputChooser by remember { mutableStateOf(false) }
    ScreenFrame(back = back, title = target.name) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .16f), shape = RoundedCornerShape(50)) {
                Text("● В сети", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Speaker, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(54.dp))
                Spacer(Modifier.height(8.dp))
                Text("Управление плеером", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Доступные команды", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!directory.mayControlMedia) {
            Spacer(Modifier.height(12.dp))
            Notice(if ("media.control" !in directory.grantedScopes) "Нет разрешения media.control." else "Плеер недоступен для управления.")
        } else {
            target.capability<ControlCapability.Playback>()?.let { playback ->
                Spacer(Modifier.height(20.dp))
                PlaybackControls(playback.actions, target, commands, dispatch)
            }
            target.capability<ControlCapability.Volume>()?.let { volume ->
                Spacer(Modifier.height(20.dp))
                VolumeControls(volume, target, commands, dispatch)
            }
            target.capability<ControlCapability.Chromecast>()?.let { cast ->
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { outputChooser = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Cast, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Вывод Chromecast")
                }
                if (ChromecastAction.Discover in cast.actions && receivers.none { it.validAt(Instant.now()) }) {
                    Text("Найдите приёмники, чтобы выбрать вывод.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            target.capability<ControlCapability.Relay>()?.let { relay ->
                Spacer(Modifier.height(14.dp))
                RelayControls(relay, target, commands, dispatch)
            }
        }
        latestStatus(target, commands)?.let { status ->
            Spacer(Modifier.height(16.dp))
            Notice(commandStatus(status), error = status.phase == CommandPhase.Failed || status.phase == CommandPhase.Cancelled || status.phase == CommandPhase.Expired)
        }
    }
    if (outputChooser) ChromecastOutputSheet(
        target = target,
        cast = target.capability<ControlCapability.Chromecast>(),
        receivers = receivers,
        commands = commands,
        dismiss = { outputChooser = false },
        dispatch = dispatch,
    )
}

@Composable
private fun PlaybackControls(actions: Set<PlaybackAction>, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit) {
    Text("Воспроизведение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (PlaybackAction.Previous in actions) RoundCommandButton(Icons.Default.SkipPrevious, "Предыдущая", RemoteCommand.Previous, target, commands, dispatch)
        when {
            PlaybackAction.Pause in actions -> RoundCommandButton(Icons.Default.Pause, "Пауза", RemoteCommand.Pause, target, commands, dispatch, primary = true)
            PlaybackAction.Play in actions -> RoundCommandButton(Icons.Default.PlayArrow, "Играть", RemoteCommand.Play, target, commands, dispatch, primary = true)
        }
        if (PlaybackAction.Stop in actions) RoundCommandButton(Icons.Default.Stop, "Остановить", RemoteCommand.Stop, target, commands, dispatch)
        if (PlaybackAction.Next in actions) RoundCommandButton(Icons.Default.SkipNext, "Следующая", RemoteCommand.Next, target, commands, dispatch)
    }
}

@Composable
private fun RoundCommandButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, command: RemoteCommand, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit, primary: Boolean = false) {
    val inFlight = commands.values.any { it.targetId == target.id && it.actionKey == command.key && it.inFlight }
    IconButton(
        onClick = { dispatch(command) },
        enabled = !inFlight,
        modifier = Modifier.size(if (primary) 68.dp else 54.dp).clip(CircleShape).background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(icon, description, tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (primary) 34.dp else 28.dp))
    }
}

@Composable
private fun VolumeControls(volume: ControlCapability.Volume, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit) {
    Text("Громкость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("Шаг ${volume.step}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommandButton(Icons.AutoMirrored.Filled.VolumeDown, "Тише", RemoteCommand.ChangeVolume(-volume.step), target, commands, dispatch, Modifier.weight(1f))
        CommandButton(Icons.AutoMirrored.Filled.VolumeUp, "Громче", RemoteCommand.ChangeVolume(volume.step), target, commands, dispatch, Modifier.weight(1f))
        if (volume.mute) CommandButton(Icons.AutoMirrored.Filled.VolumeOff, "Без звука", RemoteCommand.SetMute(true), target, commands, dispatch, Modifier.weight(1f))
    }
}

@Composable
private fun RelayControls(relay: ControlCapability.Relay, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit) {
    Text("Relay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (RelayAction.Start in relay.actions) CommandButton(Icons.Default.PlayArrow, "Запустить", RemoteCommand.StartRelay, target, commands, dispatch, Modifier.weight(1f))
        if (RelayAction.Stop in relay.actions) CommandButton(Icons.Default.Pause, "Остановить", RemoteCommand.StopRelay, target, commands, dispatch, Modifier.weight(1f))
    }
    if (RelayAction.SetMode in relay.actions) relay.modes.sorted().forEach { mode ->
        OutlinedButton(onClick = { dispatch(RemoteCommand.SetRelayMode(mode)) }, modifier = Modifier.padding(top = 8.dp)) { Text("Режим: $mode") }
    }
}

@Composable
private fun CommandButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, command: RemoteCommand, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit, modifier: Modifier = Modifier) {
    val inFlight = commands.values.any { it.targetId == target.id && it.actionKey == command.key && it.inFlight }
    OutlinedButton(onClick = { dispatch(command) }, enabled = !inFlight, modifier = modifier) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChromecastOutputSheet(target: ControllerTarget, cast: ControlCapability.Chromecast?, receivers: List<EphemeralReceiver>, commands: Map<String, CommandLifecycle>, dismiss: () -> Unit, dispatch: (RemoteCommand) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Куда звучит?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Выберите приёмник для «${target.name}»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (cast != null && ChromecastAction.Discover in cast.actions) {
                CommandButton(Icons.Default.Refresh, "Найти приёмники", RemoteCommand.Discover, target, commands, dispatch, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            val validReceivers = receivers.filter { it.validAt(Instant.now()) }
            if (validReceivers.isEmpty()) {
                Text("Доступных Chromecast-приёмников пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
            } else validReceivers.forEach { receiver ->
                OutlinedButton(onClick = { dispatch(RemoteCommand.Connect(receiver.receiverId)); dismiss() }, enabled = ChromecastAction.Connect in cast?.actions.orEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(Icons.Default.Cast, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(receiver.displayName, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                }
            }
            if (cast != null && ChromecastAction.Disconnect in cast.actions) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                OutlinedButton(onClick = { dispatch(RemoteCommand.Disconnect); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Отключить Chromecast") }
            }
        }
    }
}

@Composable
private fun ScreenFrame(back: () -> Unit, title: String, action: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                action?.let { IconButton(onClick = it) { Icon(Icons.Default.Refresh, "Обновить") } }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
private fun EmptyDevices(message: String, actionLabel: String?, action: (() -> Unit)?, error: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Devices, null, modifier = Modifier.size(48.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && action != null) Button(onClick = action, modifier = Modifier.padding(top = 18.dp)) { Text(actionLabel) }
    }
}

@Composable
private fun Notice(message: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.error.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun deviceStatus(target: ControllerTarget): String = when {
    target.presence == TargetPresence.Offline -> "Не в сети"
    target.freshness == TargetFreshness.Stale -> "Данные устарели"
    target.freshness == TargetFreshness.Unknown -> "Состояние неизвестно"
    DeviceRole.Player !in target.roles -> "Не плеер"
    else -> "В сети"
}

private fun latestStatus(target: ControllerTarget, commands: Map<String, CommandLifecycle>): CommandLifecycle? =
    commands.values.filter { it.targetId == target.id }.maxByOrNull { it.commandId }

private fun commandStatus(command: CommandLifecycle): String = when (command.phase) {
    CommandPhase.Pending -> "Команда отправляется…"
    CommandPhase.Received -> "Сервер получил команду."
    CommandPhase.Accepted -> "Плеер принял команду…"
    CommandPhase.AwaitingState -> command.detail ?: "Обновляем фактическое состояние…"
    CommandPhase.Succeeded -> command.detail ?: "Состояние плеера подтверждено."
    CommandPhase.Failed, CommandPhase.Cancelled, CommandPhase.Expired -> command.detail ?: "Команда не выполнена."
}
