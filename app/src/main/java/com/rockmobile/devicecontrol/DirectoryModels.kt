package com.rockmobile.devicecontrol

/** Domain/UI projection deliberately excludes DTOs, commands, credentials and protocol frames. */
data class ControllerTarget(
    val id: String,
    val name: String,
    val type: String,
    val roles: Set<DeviceRole>,
    val knownCapabilities: Set<KnownCapability>,
    val presence: TargetPresence,
    val freshness: TargetFreshness,
) {
    val usable: Boolean get() = presence == TargetPresence.Online && freshness == TargetFreshness.Fresh
}

enum class DeviceRole { Controller, Player, DisplaySurface, VoiceEndpoint, SensorSource, Actuator, IntegrationAdapter, Unknown }
enum class KnownCapability { Playback, Station, Volume, Chromecast, Relay, Display, Sensor, Actuator, VoiceInput }
enum class TargetPresence { Online, Offline }
enum class TargetFreshness { Fresh, Stale, Unknown }

sealed interface TargetDirectoryState {
    data object Inactive : TargetDirectoryState
    data object Loading : TargetDirectoryState
    data class Available(val targets: List<ControllerTarget>, val selectedTargetId: String?, val notice: String? = null) : TargetDirectoryState {
        val requiresSelection: Boolean get() = selectedTargetId == null
    }
    data class Unavailable(val message: String, val scopeMissing: Boolean = false) : TargetDirectoryState
}

data class ControllerSession(val userId: String, val deviceId: String, val accessToken: String)

internal fun DirectoryEntryDto.toTarget(): ControllerTarget {
    require(id.isNotBlank() && displayName.isNotBlank() && type.isNotBlank()) { "Invalid directory device" }
    require(capabilities.revision > 0 && capabilities.items.size <= 32) { "Invalid capabilities" }
    return ControllerTarget(
        id = id,
        name = displayName,
        type = type,
        roles = roles.map(::role).toSet(),
        knownCapabilities = capabilities.items.mapNotNull(::capability).toSet(),
        presence = if (presence.status == "online") TargetPresence.Online else if (presence.status == "offline") TargetPresence.Offline else throw IllegalArgumentException("Unknown presence"),
        freshness = when (freshness.status) { "fresh" -> TargetFreshness.Fresh; "stale" -> TargetFreshness.Stale; "unknown" -> TargetFreshness.Unknown; else -> throw IllegalArgumentException("Unknown freshness") },
    )
}

private fun role(value: String) = when (value) {
    "controller" -> DeviceRole.Controller; "player" -> DeviceRole.Player; "display_surface" -> DeviceRole.DisplaySurface
    "voice_endpoint" -> DeviceRole.VoiceEndpoint; "sensor_source" -> DeviceRole.SensorSource; "actuator" -> DeviceRole.Actuator
    "integration_adapter" -> DeviceRole.IntegrationAdapter; else -> DeviceRole.Unknown
}
private fun capability(value: CapabilityDto) = when (value.name) {
    "media.playback" -> KnownCapability.Playback; "media.station" -> KnownCapability.Station; "media.volume" -> KnownCapability.Volume
    "media.chromecast" -> KnownCapability.Chromecast; "media.relay" -> KnownCapability.Relay; "display.presentation" -> KnownCapability.Display
    "entity.sensor" -> KnownCapability.Sensor; "entity.actuator" -> KnownCapability.Actuator; "voice.input" -> KnownCapability.VoiceInput
    else -> null
}
