package com.rockmobile.devicecontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant

/** Remote-player controls only. They never call the phone [PlaybackController]. */
@Composable
fun CapabilityControls(
    directory: TargetDirectoryState,
    commands: Map<String, CommandLifecycle>,
    receivers: List<EphemeralReceiver>,
    dispatch: (RemoteCommand) -> Unit,
) {
    val available = directory as? TargetDirectoryState.Available
    val target = available?.selectedTarget
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Управление выбранным плеером", style = MaterialTheme.typography.titleMedium)
        if (available == null || target == null) {
            Text("Выберите доступный плеер, чтобы отправлять команды только ему.")
            return
        }
        if (!available.mayControlMedia) {
            Text(if ("media.control" !in available.grantedScopes) "Нет разрешения media.control." else "Плеер не в сети или его состояние устарело.", color = MaterialTheme.colorScheme.error)
            return
        }
        target.capability<ControlCapability.Playback>()?.let { playback ->
            Text("Воспроизведение")
            Row { playback.actions.forEach { action -> ActionButton(action, target, commands, dispatch) } }
        }
        target.capability<ControlCapability.Station>()?.let { station ->
            Text("Источник станции: ${station.sources.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
        }
        target.capability<ControlCapability.Volume>()?.let { volume ->
            Text("Громкость (${volume.minimum}–${volume.maximum}, шаг ${volume.step})")
            Row {
                CommandButton("−", RemoteCommand.ChangeVolume(-volume.step), target, commands, dispatch)
                CommandButton("+", RemoteCommand.ChangeVolume(volume.step), target, commands, dispatch)
                if (volume.mute) CommandButton("Выключить звук", RemoteCommand.SetMute(true), target, commands, dispatch)
            }
        }
        target.capability<ControlCapability.Chromecast>()?.let { cast ->
            Text("Chromecast")
            if (ChromecastAction.Discover in cast.actions) CommandButton("Найти приёмники", RemoteCommand.Discover, target, commands, dispatch)
            receivers.filter { it.validAt(Instant.now()) }.forEach { receiver ->
                if (ChromecastAction.Connect in cast.actions) CommandButton("Подключить: ${receiver.displayName}", RemoteCommand.Connect(receiver.receiverId), target, commands, dispatch)
            }
            if (ChromecastAction.Disconnect in cast.actions) CommandButton("Отключить Chromecast", RemoteCommand.Disconnect, target, commands, dispatch)
        }
        target.capability<ControlCapability.Relay>()?.let { relay ->
            Text("Relay")
            if (RelayAction.Start in relay.actions) CommandButton("Запустить relay", RemoteCommand.StartRelay, target, commands, dispatch)
            if (RelayAction.Stop in relay.actions) CommandButton("Остановить relay", RemoteCommand.StopRelay, target, commands, dispatch)
            if (RelayAction.SetMode in relay.actions) relay.modes.forEach { mode -> CommandButton("Режим: $mode", RemoteCommand.SetRelayMode(mode), target, commands, dispatch) }
        }
        commands.values.filter { it.targetId == target.id }.maxByOrNull { it.commandId }?.let { command ->
            Text(commandStatus(command), color = if (command.phase == CommandPhase.Failed || command.phase == CommandPhase.Cancelled || command.phase == CommandPhase.Expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable private fun ActionButton(action: PlaybackAction, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit) {
    val command = when (action) { PlaybackAction.Play -> RemoteCommand.Play; PlaybackAction.Pause -> RemoteCommand.Pause; PlaybackAction.Stop -> RemoteCommand.Stop; PlaybackAction.Next -> RemoteCommand.Next; PlaybackAction.Previous -> RemoteCommand.Previous }
    CommandButton(action.name, command, target, commands, dispatch)
}

@Composable private fun CommandButton(label: String, command: RemoteCommand, target: ControllerTarget, commands: Map<String, CommandLifecycle>, dispatch: (RemoteCommand) -> Unit) {
    val inFlight = commands.values.any { it.targetId == target.id && it.actionKey == command.key && it.inFlight }
    OutlinedButton(onClick = { dispatch(command) }, enabled = !inFlight) { Text(label) }
}

private fun commandStatus(command: CommandLifecycle): String = when (command.phase) {
    CommandPhase.Pending -> "Команда отправляется…"; CommandPhase.Received -> "Сервер получил команду."; CommandPhase.Accepted -> "Плеер принял команду…"
    CommandPhase.AwaitingState -> "Плеер подтвердил выполнение; обновляем фактическое состояние…"; CommandPhase.Succeeded -> "Состояние плеера подтверждено."
    CommandPhase.Failed, CommandPhase.Cancelled, CommandPhase.Expired -> command.detail ?: "Команда не выполнена."
}
