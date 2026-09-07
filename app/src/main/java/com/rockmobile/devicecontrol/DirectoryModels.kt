package com.rockmobile.devicecontrol

/** Controller-facing projection. Advertised functions and observed availability remain separate. */
data class ControllerTarget(
    val id: String,
    val name: String,
    val type: String,
    val roles: Set<DeviceRole>,
    val capabilities: Set<ControlCapability>,
    val presence: TargetPresence,
    val freshness: TargetFreshness,
) {
    val usable: Boolean get() = DeviceRole.Player in roles && presence == TargetPresence.Online && freshness == TargetFreshness.Fresh
    val knownCapabilities: Set<KnownCapability> get() = capabilities.map { it.kind }.toSet()
    inline fun <reified T : ControlCapability> capability(): T? = capabilities.filterIsInstance<T>().singleOrNull()
}

enum class DeviceRole { Controller, Player, DisplaySurface, VoiceEndpoint, SensorSource, Actuator, IntegrationAdapter, Unknown }
enum class KnownCapability { Playback, Station, Volume, Chromecast, Relay }
enum class TargetPresence { Online, Offline }
enum class TargetFreshness { Fresh, Stale, Unknown }

sealed interface ControlCapability {
    val kind: KnownCapability
    data class Playback(val actions: Set<PlaybackAction>) : ControlCapability { override val kind = KnownCapability.Playback }
    data class Station(val sources: Set<StationSource>) : ControlCapability { override val kind = KnownCapability.Station }
    data class Volume(val minimum: Int, val maximum: Int, val step: Int, val mute: Boolean) : ControlCapability { override val kind = KnownCapability.Volume }
    data class Chromecast(val actions: Set<ChromecastAction>, val discoveryTtlSeconds: Int) : ControlCapability { override val kind = KnownCapability.Chromecast }
    data class Relay(val actions: Set<RelayAction>, val modes: Set<String>) : ControlCapability { override val kind = KnownCapability.Relay }
}

enum class PlaybackAction { Play, Pause, Stop, Next, Previous }
enum class StationSource { RockserverCatalog, DirectStream }
enum class ChromecastAction { Discover, Connect, Disconnect }
enum class RelayAction { Start, Stop, SetMode }

sealed interface TargetDirectoryState {
    data object Inactive : TargetDirectoryState
    data object Loading : TargetDirectoryState
    data class Available(
        val targets: List<ControllerTarget>,
        val selectedTargetId: String?,
        val grantedScopes: Set<String>,
        val notice: String? = null,
    ) : TargetDirectoryState {
        val requiresSelection: Boolean get() = selectedTargetId == null
        val selectedTarget: ControllerTarget? get() = targets.singleOrNull { it.id == selectedTargetId }
        val mayControlMedia: Boolean get() = "media.control" in grantedScopes && selectedTarget?.usable == true
    }
    data class Unavailable(val message: String, val scopeMissing: Boolean = false) : TargetDirectoryState
}

data class ControllerSession(val userId: String, val deviceId: String, val accessToken: String)

internal fun DirectoryEntryDto.toTarget(): ControllerTarget {
    require(id.isNotBlank() && displayName.isNotBlank() && type.isNotBlank()) { "Invalid directory device" }
    require(capabilities.revision > 0 && capabilities.items.size <= 32) { "Invalid capabilities" }
    return ControllerTarget(
        id = id, name = displayName, type = type, roles = roles.map(::role).toSet(),
        capabilities = capabilities.items.mapNotNull(CapabilityDto::toKnownCapability).toSet(),
        presence = when (presence.status) { "online" -> TargetPresence.Online; "offline" -> TargetPresence.Offline; else -> throw IllegalArgumentException("Unknown presence") },
        freshness = when (freshness.status) { "fresh" -> TargetFreshness.Fresh; "stale" -> TargetFreshness.Stale; "unknown" -> TargetFreshness.Unknown; else -> throw IllegalArgumentException("Unknown freshness") },
    )
}

private fun role(value: String) = when (value) {
    "controller" -> DeviceRole.Controller; "player" -> DeviceRole.Player; "display_surface" -> DeviceRole.DisplaySurface
    "voice_endpoint" -> DeviceRole.VoiceEndpoint; "sensor_source" -> DeviceRole.SensorSource; "actuator" -> DeviceRole.Actuator
    "integration_adapter" -> DeviceRole.IntegrationAdapter; else -> DeviceRole.Unknown
}

private fun CapabilityDto.toKnownCapability(): ControlCapability? {
    require(version >= 1) { "Invalid capability version" }
    return when (name) {
        "media.playback" -> ControlCapability.Playback(actions.enumSet(::playbackAction))
        "media.station" -> ControlCapability.Station(sources.enumSet(::stationSource))
        "media.volume" -> ControlCapability.Volume(minimum ?: malformed(), maximum ?: malformed(), step ?: malformed(), mute ?: malformed()).also {
            require(it.minimum == 0 && it.maximum == 100 && it.step in 1..100)
        }
        "media.chromecast" -> ControlCapability.Chromecast(actions.enumSet(::chromecastAction), discoveryTtlSeconds ?: malformed()).also { require(it.discoveryTtlSeconds in 5..300) }
        "media.relay" -> ControlCapability.Relay(actions.enumSet(::relayAction), (modes ?: malformed()).toSet()).also { require(it.modes.size <= 8 && it.modes.all { mode -> mode.matches(Regex("^[a-z][a-z0-9_]*$")) }) }
        else -> null
    }
}

private fun malformed(): Nothing = throw IllegalArgumentException("Malformed known capability")
private fun <T> List<String>?.enumSet(mapper: (String) -> T?): Set<T> {
    require(this != null && size == toSet().size) { "Malformed capability values" }
    return map { mapper(it) ?: throw IllegalArgumentException("Unknown known capability value") }.toSet()
}
private fun playbackAction(value: String) = PlaybackAction.entries.singleOrNull { it.name.equals(value, true) }
private fun stationSource(value: String) = when (value) { "rockserver_catalog" -> StationSource.RockserverCatalog; "direct_stream" -> StationSource.DirectStream; else -> null }
private fun chromecastAction(value: String) = ChromecastAction.entries.singleOrNull { it.name.equals(value, true) }
private fun relayAction(value: String) = when (value) { "start" -> RelayAction.Start; "stop" -> RelayAction.Stop; "set_mode" -> RelayAction.SetMode; else -> null }
