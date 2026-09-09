package com.rockmobile.devicecontrol

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

/** Typed executable v1 subset: device targets only; display/entity commands deliberately do not exist here. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("name")
sealed interface RemoteCommand {
    val key: String
    @Serializable @SerialName("playback.play") data object Play : RemoteCommand { override val key = "playback.play" }
    @Serializable @SerialName("playback.pause") data object Pause : RemoteCommand { override val key = "playback.pause" }
    @Serializable @SerialName("playback.stop") data object Stop : RemoteCommand { override val key = "playback.stop" }
    @Serializable @SerialName("playback.next") data object Next : RemoteCommand { override val key = "playback.next" }
    @Serializable @SerialName("playback.previous") data object Previous : RemoteCommand { override val key = "playback.previous" }
    @Serializable @SerialName("station.play_station") data class PlayStation(@SerialName("station_id") val stationId: String) : RemoteCommand { override val key = "station.play_station:" + stationId }
    @Serializable @SerialName("station.play_stream") data class PlayStream(val source: String, @SerialName("stream_uri") val streamUri: String) : RemoteCommand { override val key = "station.play_stream:" + streamUri }
    @Serializable @SerialName("volume.set_volume") data class SetVolume(val level: Int) : RemoteCommand { override val key = "volume.set_volume:" + level }
    @Serializable @SerialName("volume.change_volume") data class ChangeVolume(val delta: Int) : RemoteCommand { override val key = "volume.change_volume:" + delta }
    @Serializable @SerialName("volume.set_mute") data class SetMute(val muted: Boolean) : RemoteCommand { override val key = "volume.set_mute:" + muted }
    @Serializable @SerialName("chromecast.discover") data object Discover : RemoteCommand { override val key = "chromecast.discover" }
    @Serializable @SerialName("chromecast.connect") data class Connect(@SerialName("receiver_id") val receiverId: String) : RemoteCommand { override val key = "chromecast.connect:" + receiverId }
    @Serializable @SerialName("chromecast.disconnect") data object Disconnect : RemoteCommand { override val key = "chromecast.disconnect" }
    @Serializable @SerialName("relay.start") data object StartRelay : RemoteCommand { override val key = "relay.start" }
    @Serializable @SerialName("relay.stop") data object StopRelay : RemoteCommand { override val key = "relay.stop" }
    @Serializable @SerialName("relay.set_mode") data class SetRelayMode(val mode: String) : RemoteCommand { override val key = "relay.set_mode:" + mode }
}

@Serializable internal data class DeviceTargetDto(@SerialName("device_id") val deviceId: String)
@Serializable internal data class DeviceCommandPayloadDto(
    @SerialName("command_id") val commandId: String,
    val target: DeviceTargetDto,
    @SerialName("deadline_at") val deadlineAt: String,
    val body: RemoteCommand,
)

internal fun newCommand(targetId: String, body: RemoteCommand, now: Instant): DeviceCommandPayloadDto =
    DeviceCommandPayloadDto(UUID.randomUUID().toString(), DeviceTargetDto(targetId), now.plusSeconds(10).toString(), body)

enum class CommandPhase { Pending, Received, Accepted, AwaitingState, Succeeded, Failed, Cancelled, Expired }
data class CommandLifecycle(
    val commandId: String,
    val targetId: String,
    val actionKey: String,
    val phase: CommandPhase,
    val detail: String? = null,
) { val inFlight: Boolean get() = phase == CommandPhase.Pending || phase == CommandPhase.Received || phase == CommandPhase.Accepted || phase == CommandPhase.AwaitingState }

data class EphemeralReceiver(val receiverId: String, val displayName: String, val expiresAt: Instant) {
    fun validAt(now: Instant): Boolean = now.isBefore(expiresAt)
}

/** Determines whether a station represents a direct stream URI rather than a RockServer catalog station. */
fun isDirectStreamStation(station: com.rockmobile.domain.model.Station): Boolean =
    station.id.startsWith("direct_stream") ||
        station.id.startsWith("http://") ||
        station.id.startsWith("https://") ||
        station.id.contains("://")

/**
 * Builds a typed station.play_station command for a catalog station.
 * Returns null if the station is a direct stream or if the target does not support RockServer catalog playback.
 */
fun buildPlayStationCommand(
    station: com.rockmobile.domain.model.Station,
    target: ControllerTarget? = null,
): RemoteCommand.PlayStation? {
    if (isDirectStreamStation(station)) return null
    if (station.id.length !in 1..128) return null
    if (target != null) {
        val stationCap = target.capability<ControlCapability.Station>() ?: return null
        if (StationSource.RockserverCatalog !in stationCap.sources) return null
    }
    return RemoteCommand.PlayStation(station.id)
}

data class DevicePlaySupport(
    val supported: Boolean,
    val reason: String? = null,
    val target: ControllerTarget? = null,
)

fun checkDevicePlaySupport(state: TargetDirectoryState): DevicePlaySupport = when (state) {
    TargetDirectoryState.Inactive, TargetDirectoryState.Loading ->
        DevicePlaySupport(false, "Устройство не выбрано")
    is TargetDirectoryState.Unavailable ->
        DevicePlaySupport(false, state.message)
    is TargetDirectoryState.Available -> {
        val target = state.selectedTarget
        if (target == null) {
            DevicePlaySupport(false, "Устройство не выбрано")
        } else if (target.presence == TargetPresence.Offline) {
            DevicePlaySupport(false, "Устройство offline", target)
        } else if (target.freshness != TargetFreshness.Fresh) {
            DevicePlaySupport(false, "Данные устройства устарели", target)
        } else if (DeviceRole.Player !in target.roles) {
            DevicePlaySupport(false, "Устройство не является плеером", target)
        } else if ("media.control" !in state.grantedScopes) {
            DevicePlaySupport(false, "Нет разрешения media.control", target)
        } else {
            val stationCap = target.capability<ControlCapability.Station>()
            if (stationCap == null || StationSource.RockserverCatalog !in stationCap.sources) {
                DevicePlaySupport(false, "Устройство не поддерживает каталог станций", target)
            } else {
                DevicePlaySupport(true, null, target)
            }
        }
    }
}
