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
