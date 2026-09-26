package com.rockmobile.devicecontrol

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridges the pure live-playback reducer to the existing directory repository and command
 * lifecycle. It owns no optimistic presentation state of its own: every visible value comes
 * from [state], and the mini-player and StationPlayerScreen read this one [StateFlow].
 */
class LivePlaybackStore internal constructor(
    private val repository: TargetDirectoryRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LivePlaybackState())
    val state: StateFlow<LivePlaybackState> = _state.asStateFlow()
    private val seenCommandPhases = mutableMapOf<String, CommandPhase>()

    init {
        scope.launch { repository.state.collect(::onDirectory) }
        scope.launch { repository.commands.collect(::onCommands) }
    }

    /**
     * Sends `station.play_station` for a catalog station on the selected target (or an
     * explicit usable target, which is selected first). Refusals before sending surface a
     * failure with the blocking reason instead of any fabricated playback state.
     */
    fun requestPlay(stationId: String, targetId: String? = null, now: Instant = Instant.now()) {
        val target = ensureSelectedTarget(targetId)
        // After selection, re-run the full support check so a capability refusal (not just
        // offline/stale) is reported with its exact reason instead of a transport message.
        val supportReason = if (target == null) playSupportReason() else checkDevicePlaySupport(repository.state.value).takeIf { !it.supported }?.reason
        if (target == null || supportReason != null) {
            return apply(
                LivePlaybackEvent.CommandRejected(
                    rejectedTargetId(targetId),
                    stationId,
                    supportReason ?: "Воспроизведение на устройстве недоступно.",
                ),
            )
        }
        val commandId = repository.dispatch(RemoteCommand.PlayStation(stationId), now)
        if (commandId == null) {
            apply(LivePlaybackEvent.CommandRejected(target.id, stationId, "Команда уже выполняется или соединение потеряно."))
            return
        }
        apply(
            LivePlaybackEvent.IntentCreated(
                PendingPlaybackIntent(
                    commandId = commandId,
                    targetId = target.id,
                    kind = PendingIntentKind.Play(stationId),
                    stateRevisionAtSend = confirmedRevision(target.id),
                    sentAtMs = now.toEpochMilli(),
                ),
            ),
        )
    }

    /** Sends `playback.stop` on the selected (or explicit) target; the intent clears on the stopped echo. */
    fun requestStop(targetId: String? = null, now: Instant = Instant.now()) {
        val target = ensureSelectedTarget(targetId)
            ?: return apply(LivePlaybackEvent.CommandRejected(rejectedTargetId(targetId), null, stopSupportReason() ?: "Остановка на устройстве недоступна."))
        if (PlaybackAction.Stop !in (target.capability<ControlCapability.Playback>()?.actions ?: emptySet())) {
            return apply(LivePlaybackEvent.CommandRejected(target.id, null, "Устройство не поддерживает остановку."))
        }
        val commandId = repository.dispatch(RemoteCommand.Stop, now)
        if (commandId == null) {
            apply(LivePlaybackEvent.CommandRejected(target.id, null, "Команда уже выполняется или соединение потеряно."))
            return
        }
        apply(
            LivePlaybackEvent.IntentCreated(
                PendingPlaybackIntent(
                    commandId = commandId,
                    targetId = target.id,
                    kind = PendingIntentKind.Stop,
                    stateRevisionAtSend = confirmedRevision(target.id),
                    sentAtMs = now.toEpochMilli(),
                ),
            ),
        )
    }

    /** Begins a volume gesture: network echoes stop moving the slider until [finishVolumeDrag]. */
    fun beginVolumeDrag(targetId: String) {
        val level = _state.value.presentTarget(targetId)?.volumePercent
            ?: (_state.value.targets[targetId]?.confirmed?.volumeLevel)
            ?: 0
        apply(LivePlaybackEvent.VolumeDragStarted(targetId, level))
    }

    fun updateVolumeDrag(level: Int) = apply(LivePlaybackEvent.VolumeDragged(level))

    /** Ends the gesture: the reducer requests exactly one `volume.set_volume` via [CommitVolume]. */
    fun finishVolumeDrag(now: Instant = Instant.now()) {
        val transition = reduceLivePlayback(_state.value, LivePlaybackEvent.VolumeDragFinished)
        _state.value = transition.state
        transition.effects.forEach { effect -> commitVolume(effect, now) }
    }

    fun consumeOverrideNotice() = apply(LivePlaybackEvent.OverrideNoticeConsumed)

    private fun commitVolume(effect: CommitVolume, now: Instant) {
        val directory = repository.state.value as? TargetDirectoryState.Available
        val target = directory?.targets?.singleOrNull { it.id == effect.targetId }
        val capability = target?.capability<ControlCapability.Volume>()
        // Volume needs only a usable selected player target with the volume capability and scope.
        val allowed = directory != null && target != null && target.usable &&
            target.id == selectedTarget()?.id && "media.control" in directory.grantedScopes && capability != null
        if (!allowed || capability == null) {
            apply(LivePlaybackEvent.CommandRejected(effect.targetId, null, "Громкость устройства недоступна."))
            return
        }
        // Snap to the advertised step grid so the device never receives an invalid level.
        val snapped = effect.level.coerceIn(capability.minimum, capability.maximum)
            .let { level -> capability.minimum + ((level - capability.minimum + capability.step / 2) / capability.step) * capability.step }
            .coerceIn(capability.minimum, capability.maximum)
        val commandId = repository.dispatch(RemoteCommand.SetVolume(snapped), now)
        if (commandId == null) {
            apply(LivePlaybackEvent.CommandRejected(effect.targetId, null, "Громкость не отправлена: устройство недоступно."))
            return
        }
        apply(
            LivePlaybackEvent.VolumeIntentCreated(
                PendingVolumeIntent(
                    commandId = commandId,
                    targetId = effect.targetId,
                    level = snapped,
                    stateRevisionAtSend = confirmedRevision(effect.targetId),
                    sentAtMs = now.toEpochMilli(),
                ),
            ),
        )
    }

    private fun onDirectory(directory: TargetDirectoryState) {
        if (directory !is TargetDirectoryState.Available) return
        val present = directory.targets.map(ControllerTarget::id).toSet()
        _state.value.targets.keys.filter { it !in present }.forEach { apply(LivePlaybackEvent.TargetRemoved(it)) }
        directory.targets.forEach { target ->
            when (val runtime = target.runtimeState) {
                is TargetRuntimeState.Known ->
                    apply(
                        LivePlaybackEvent.TargetStateUpdated(
                            ConfirmedDeviceState(
                                targetId = target.id,
                                stateRevision = runtime.stateRevision,
                                observedAt = runtime.observedAt,
                                status = runtime.playbackStatus,
                                stationId = runtime.stationId,
                                trackTitle = runtime.trackTitle,
                                volumeLevel = runtime.volumeLevel,
                                muted = runtime.muted,
                            ),
                        ),
                    )
                TargetRuntimeState.Unknown -> apply(LivePlaybackEvent.TargetStateUnknown(target.id))
            }
        }
    }

    private fun onCommands(commands: Map<String, CommandLifecycle>) {
        if (seenCommandPhases.size > 64) seenCommandPhases.keys.retainAll(commands.keys)
        commands.forEach { (id, lifecycle) ->
            if (seenCommandPhases[id] == lifecycle.phase) return@forEach
            seenCommandPhases[id] = lifecycle.phase
            when (lifecycle.phase) {
                CommandPhase.Failed, CommandPhase.Expired, CommandPhase.Cancelled ->
                    apply(LivePlaybackEvent.CommandFailed(id, lifecycle.targetId, lifecycle.detail))
                CommandPhase.Succeeded, CommandPhase.AwaitingState -> apply(LivePlaybackEvent.CommandSucceeded(id))
                else -> Unit
            }
        }
    }

    private fun apply(event: LivePlaybackEvent) {
        _state.value = reduceLivePlayback(_state.value, event).state
    }

    /**
     * Resolves the effective command target. An explicit id that differs from the current
     * selection is selected first; unusable targets surface as null with a published reason.
     */
    private fun ensureSelectedTarget(targetId: String?): ControllerTarget? {
        if (targetId != null && targetId != selectedTarget()?.id) repository.select(targetId)
        return selectedTarget()?.takeIf { it.usable && (targetId == null || it.id == targetId) }
    }

    private fun selectedTarget(): ControllerTarget? =
        (repository.state.value as? TargetDirectoryState.Available)?.selectedTarget

    private fun confirmedRevision(targetId: String): Long =
        _state.value.targets[targetId]?.confirmed?.stateRevision ?: 0

    private fun playSupportReason(): String? = checkDevicePlaySupport(repository.state.value).reason

    /** Stop only needs a usable player target with the media.control scope; catalog stations are irrelevant. */
    private fun stopSupportReason(): String? = when (val directory = repository.state.value) {
        !is TargetDirectoryState.Available -> "Устройство не выбрано"
        else -> {
            val target = directory.selectedTarget
            when {
                target == null -> "Устройство не выбрано"
                target.presence == TargetPresence.Offline -> "Устройство offline"
                target.freshness != TargetFreshness.Fresh -> "Данные устройства устарели"
                DeviceRole.Player !in target.roles -> "Устройство не является плеером"
                "media.control" !in directory.grantedScopes -> "Нет разрешения media.control"
                else -> null
            }
        }
    }

    private fun rejectedTargetId(targetId: String?): String? = targetId ?: selectedTarget()?.id
}
