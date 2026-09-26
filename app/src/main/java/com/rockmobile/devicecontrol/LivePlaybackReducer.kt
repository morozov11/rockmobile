package com.rockmobile.devicecontrol

import com.rockmobile.domain.model.Station

/**
 * Pure presentation reducer for authoritative live playback (ТЗ §4.4 state reconciliation
 * matrix). It keeps `lastConfirmedState` (revisioned device snapshots), `pendingIntent`
 * (local commands awaiting confirmation) and the volume gesture strictly separate, and it
 * is the single source of presentation state for the mini-player and StationPlayerScreen.
 * It has no Android or coroutine dependencies so every matrix row is unit-testable.
 */

/** Authoritative playback/volume snapshot mirrored from one directory `runtime_state` projection. */
data class ConfirmedDeviceState(
    val targetId: String,
    val stateRevision: Long,
    val observedAt: String,
    val status: PlaybackStatus?,
    val stationId: String?,
    val volumeLevel: Int?,
    val muted: Boolean?,
    val trackTitle: String? = null,
)

/** What a locally dispatched command is still waiting to see confirmed by device state. */
sealed interface PendingIntentKind {
    data class Play(val stationId: String) : PendingIntentKind
    data object Stop : PendingIntentKind
}

/** A user intent in flight; never displayed as real playback, only as «ожидаем подтверждения». */
data class PendingPlaybackIntent(
    val commandId: String,
    val targetId: String,
    val kind: PendingIntentKind,
    /** Confirmed state revision observed when the command left the phone; resolves echo races. */
    val stateRevisionAtSend: Long,
    val sentAtMs: Long,
)

/** One volume commit awaiting its device echo. */
data class PendingVolumeIntent(
    val commandId: String,
    val targetId: String,
    val level: Int,
    val stateRevisionAtSend: Long,
    val sentAtMs: Long,
)

/** Active volume gesture; while present, network echoes must not move the slider. */
data class VolumeDragState(val targetId: String, val level: Int)

/** Terminal command failure surfaced with a manual retry option; no auto-retry exists. */
data class LiveCommandFailure(
    val commandId: String?,
    val targetId: String?,
    val stationId: String?,
    val message: String,
)

/** Set when a fresher device state supersedes a pending local intent (external override). */
data class ExternalOverrideNotice(val targetId: String, val stationId: String?)

/** Per-target live state: confirmed snapshot (null = Unknown) plus at most one pending intent. */
data class LiveTargetState(
    val confirmed: ConfirmedDeviceState? = null,
    val pending: PendingPlaybackIntent? = null,
)

data class LivePlaybackState(
    val targets: Map<String, LiveTargetState> = emptyMap(),
    val volumeDrag: VolumeDragState? = null,
    val pendingVolume: PendingVolumeIntent? = null,
    val lastFailure: LiveCommandFailure? = null,
    val overrideNotice: ExternalOverrideNotice? = null,
)

internal sealed interface LivePlaybackEvent {
    data class TargetStateUpdated(val state: ConfirmedDeviceState) : LivePlaybackEvent
    data class TargetStateUnknown(val targetId: String) : LivePlaybackEvent
    data class TargetRemoved(val targetId: String) : LivePlaybackEvent
    data class IntentCreated(val intent: PendingPlaybackIntent) : LivePlaybackEvent
    data class VolumeIntentCreated(val intent: PendingVolumeIntent) : LivePlaybackEvent
    /** Terminal `succeeded` alone never means playing; the UI keeps awaiting the state echo. */
    data class CommandSucceeded(val commandId: String) : LivePlaybackEvent
    data class CommandFailed(val commandId: String, val targetId: String?, val message: String?) : LivePlaybackEvent
    /** A dispatch refused before any frame left the phone (offline/stale/no scope/capability). */
    data class CommandRejected(val targetId: String?, val stationId: String?, val message: String) : LivePlaybackEvent
    data class VolumeDragStarted(val targetId: String, val initialLevel: Int) : LivePlaybackEvent
    data class VolumeDragged(val level: Int) : LivePlaybackEvent
    data object VolumeDragFinished : LivePlaybackEvent
    data object OverrideNoticeConsumed : LivePlaybackEvent
}

/** Side effect requested by the reducer; executed exactly once by the store. */
internal data class CommitVolume(val targetId: String, val level: Int)

internal data class LivePlaybackTransition(val state: LivePlaybackState, val effects: List<CommitVolume> = emptyList())

internal fun reduceLivePlayback(state: LivePlaybackState, event: LivePlaybackEvent): LivePlaybackTransition = when (event) {
    is LivePlaybackEvent.TargetStateUpdated -> LivePlaybackTransition(applyStateUpdate(state, event.state))
    is LivePlaybackEvent.TargetStateUnknown ->
        LivePlaybackTransition(state.updateTarget(event.targetId) { it.copy(confirmed = null) })
    is LivePlaybackEvent.TargetRemoved -> LivePlaybackTransition(state.copy(targets = state.targets - event.targetId))
    is LivePlaybackEvent.IntentCreated ->
        LivePlaybackTransition(state.updateTarget(event.intent.targetId) { it.copy(pending = event.intent) }.copy(lastFailure = null))
    is LivePlaybackEvent.VolumeIntentCreated -> LivePlaybackTransition(state.copy(pendingVolume = event.intent))
    is LivePlaybackEvent.CommandSucceeded -> LivePlaybackTransition(state)
    is LivePlaybackEvent.CommandFailed -> LivePlaybackTransition(applyCommandFailed(state, event))
    is LivePlaybackEvent.CommandRejected ->
        LivePlaybackTransition(state.copy(lastFailure = LiveCommandFailure(null, event.targetId, event.stationId, event.message)))
    is LivePlaybackEvent.VolumeDragStarted ->
        LivePlaybackTransition(state.copy(volumeDrag = VolumeDragState(event.targetId, event.initialLevel)))
    is LivePlaybackEvent.VolumeDragged ->
        LivePlaybackTransition(state.copy(volumeDrag = state.volumeDrag?.copy(level = event.level)))
    LivePlaybackEvent.VolumeDragFinished -> finishVolumeDrag(state)
    LivePlaybackEvent.OverrideNoticeConsumed -> LivePlaybackTransition(state.copy(overrideNotice = null))
}

/**
 * Reconciles an incoming revisioned snapshot against confirmed state and any pending intent.
 * Older revisions are ignored (device revisions are monotonic). A newer-revision state that
 * contradicts the pending intent clears it immediately without waiting for a command timeout.
 */
private fun applyStateUpdate(state: LivePlaybackState, snapshot: ConfirmedDeviceState): LivePlaybackState {
    val entry = state.targets[snapshot.targetId] ?: LiveTargetState()
    val current = entry.confirmed
    if (current != null && snapshot.stateRevision < current.stateRevision) return state
    var updated = entry.copy(confirmed = snapshot)
    var override = state.overrideNotice
    val pending = entry.pending
    if (pending != null && snapshot.stateRevision > pending.stateRevisionAtSend) {
        when (val kind = pending.kind) {
            is PendingIntentKind.Play -> when {
                snapshot.stationId == kind.stationId && snapshot.status == PlaybackStatus.Playing ->
                    updated = updated.copy(pending = null)
                snapshot.stationId == kind.stationId && snapshot.status == PlaybackStatus.Buffering -> Unit
                snapshot.status == PlaybackStatus.Playing || snapshot.status == PlaybackStatus.Buffering -> {
                    // External override: another controller or the device itself chose station B.
                    updated = updated.copy(pending = null)
                    if (override == null) override = ExternalOverrideNotice(snapshot.targetId, snapshot.stationId)
                }
                else -> updated = updated.copy(pending = null)
            }
            PendingIntentKind.Stop -> when (snapshot.status) {
                PlaybackStatus.Stopped, PlaybackStatus.Idle -> updated = updated.copy(pending = null)
                PlaybackStatus.Playing, PlaybackStatus.Buffering -> {
                    updated = updated.copy(pending = null)
                    if (override == null) override = ExternalOverrideNotice(snapshot.targetId, snapshot.stationId)
                }
                else -> Unit
            }
        }
    }
    // Volume echo: any fresher revision carrying a value resolves the pending commit —
    // equal level means applied, a different level is an authoritative external change.
    var pendingVolume = state.pendingVolume
    if (pendingVolume?.targetId == snapshot.targetId &&
        snapshot.stateRevision > pendingVolume.stateRevisionAtSend &&
        snapshot.volumeLevel != null
    ) {
        pendingVolume = null
    }
    return state.copy(
        targets = state.targets + (snapshot.targetId to updated),
        pendingVolume = pendingVolume,
        overrideNotice = override,
    )
}

private fun applyCommandFailed(state: LivePlaybackState, event: LivePlaybackEvent.CommandFailed): LivePlaybackState {
    val message = event.message ?: "Команда не выполнена."
    val entry = event.targetId?.let(state.targets::get)
    return when {
        entry?.pending?.commandId == event.commandId -> {
            val stationId = (entry.pending?.kind as? PendingIntentKind.Play)?.stationId
            state.updateTarget(event.targetId!!) { it.copy(pending = null) }
                .copy(lastFailure = LiveCommandFailure(event.commandId, event.targetId, stationId, message))
        }
        state.pendingVolume?.commandId == event.commandId ->
            state.copy(pendingVolume = null, lastFailure = LiveCommandFailure(event.commandId, event.targetId, null, message))
        else -> state.copy(lastFailure = LiveCommandFailure(event.commandId, event.targetId, null, message))
    }
}

/**
 * Drag release commits exactly one `volume.set_volume` request. A release that matches the
 * confirmed level sends nothing: the device does not bump `state_revision` for unchanged
 * state, so a request could never be confirmed by an echo.
 */
private fun finishVolumeDrag(state: LivePlaybackState): LivePlaybackTransition {
    val drag = state.volumeDrag ?: return LivePlaybackTransition(state)
    val confirmed = state.targets[drag.targetId]?.confirmed
    val cleared = state.copy(volumeDrag = null)
    if (confirmed?.volumeLevel == drag.level) return LivePlaybackTransition(cleared)
    return LivePlaybackTransition(cleared, listOf(CommitVolume(drag.targetId, drag.level)))
}

private fun LivePlaybackState.updateTarget(targetId: String, transform: (LiveTargetState) -> LiveTargetState): LivePlaybackState =
    copy(targets = targets + (targetId to transform(targets[targetId] ?: LiveTargetState())))

/** Statuses the live UI can present for one output target. */
enum class LivePlaybackStatusUi { Unknown, Idle, Buffering, Playing, Paused, Stopped, Error, AwaitingConfirmation }

/** Presentation projection for one target; catalogue lookups stay separate from the playback fact. */
data class LiveTargetPresentation(
    val targetId: String,
    val status: LivePlaybackStatusUi,
    val confirmedStationId: String?,
    val requestedStationId: String?,
    val volumePercent: Int?,
    val muted: Boolean?,
    val volumeApplying: Boolean,
    val volumeDragging: Boolean,
    val failure: LiveCommandFailure?,
    val trackTitle: String? = null,
)

/**
 * Projects one target's live state for display. With `targetId == null` it auto-selects a
 * target that is confirmed playing or buffering (mini-player); otherwise it returns null
 * when the target has never been seen.
 */
fun LivePlaybackState.presentTarget(targetId: String?): LiveTargetPresentation? {
    val id = targetId
        ?: targets.entries.firstOrNull { it.value.confirmed?.status == PlaybackStatus.Playing || it.value.confirmed?.status == PlaybackStatus.Buffering }?.key
        ?: return null
    val entry = targets[id] ?: return null
    val pending = entry.pending
    val confirmed = entry.confirmed
    val status = when {
        pending != null -> when (val kind = pending.kind) {
            is PendingIntentKind.Play -> when {
                confirmed?.stationId == kind.stationId && confirmed.status == PlaybackStatus.Buffering -> LivePlaybackStatusUi.Buffering
                confirmed?.stationId == kind.stationId && confirmed.status == PlaybackStatus.Playing -> LivePlaybackStatusUi.Playing
                else -> LivePlaybackStatusUi.AwaitingConfirmation
            }
            PendingIntentKind.Stop -> if (confirmed?.status == PlaybackStatus.Stopped) LivePlaybackStatusUi.Stopped else LivePlaybackStatusUi.AwaitingConfirmation
        }
        else -> when (confirmed?.status) {
            PlaybackStatus.Playing -> LivePlaybackStatusUi.Playing
            PlaybackStatus.Buffering -> LivePlaybackStatusUi.Buffering
            PlaybackStatus.Paused -> LivePlaybackStatusUi.Paused
            PlaybackStatus.Stopped -> LivePlaybackStatusUi.Stopped
            PlaybackStatus.Idle -> LivePlaybackStatusUi.Idle
            PlaybackStatus.Error -> LivePlaybackStatusUi.Error
            null -> LivePlaybackStatusUi.Unknown
        }
    }
    val drag = volumeDrag?.takeIf { it.targetId == id }
    val pendingVolumeHere = pendingVolume?.takeIf { it.targetId == id }
    return LiveTargetPresentation(
        targetId = id,
        status = status,
        confirmedStationId = confirmed?.stationId,
        requestedStationId = (pending?.kind as? PendingIntentKind.Play)?.stationId,
        volumePercent = drag?.level ?: pendingVolumeHere?.level ?: confirmed?.volumeLevel,
        muted = confirmed?.muted,
        volumeApplying = pendingVolumeHere != null && drag == null,
        volumeDragging = drag != null,
        failure = lastFailure?.takeIf { it.targetId == id || it.targetId == null },
        trackTitle = confirmed?.trackTitle,
    )
}

/**
 * Catalogue presentation for a station id. It never proves that a device is playing; an id
 * missing from the local catalogue degrades to the safe label «Станция <id>» instead of the
 * previously selected station's text.
 */
fun stationDisplayTitle(stationId: String?, stations: List<Station>): String? = when {
    stationId == null -> null
    else -> stations.singleOrNull { it.id == stationId }?.name ?: "Станция $stationId"
}
