package com.rockmobile.devicecontrol

import com.rockmobile.domain.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStationCommandTest {

    private val catalogStation = Station(
        id = "rock-fm",
        name = "Rock FM",
        streamUrl = "https://stream.test/live.mp3",
    )

    private val esp32Target = ControllerTarget(
        id = "esp32-living-room",
        name = "ESP32 Radio",
        type = "esp32",
        roles = setOf(DeviceRole.Player),
        capabilities = setOf(
            ControlCapability.Playback(setOf(PlaybackAction.Play, PlaybackAction.Stop)),
            ControlCapability.Station(setOf(StationSource.RockserverCatalog)),
            ControlCapability.Volume(0, 100, 1, true),
        ),
        presence = TargetPresence.Online,
        freshness = TargetFreshness.Fresh,
    )

    private val directStreamOnlyTarget = ControllerTarget(
        id = "direct-only-player",
        name = "Direct Only Player",
        type = "legacy_player",
        roles = setOf(DeviceRole.Player),
        capabilities = setOf(
            ControlCapability.Playback(setOf(PlaybackAction.Play, PlaybackAction.Stop)),
            ControlCapability.Station(setOf(StationSource.DirectStream)),
        ),
        presence = TargetPresence.Online,
        freshness = TargetFreshness.Fresh,
    )

    @Test
    fun buildPlayStationCommand_buildsPlayStationForCatalogStation() {
        val command = buildPlayStationCommand(catalogStation, esp32Target)
        assertNotNull(command)
        assertEquals("rock-fm", command!!.stationId)
        assertEquals("station.play_station:rock-fm", command.key)
    }

    @Test
    fun buildPlayStationCommand_rejectsDirectStreamStations() {
        val directStreamPrefix = Station("direct_stream:https://example.com/stream", "Direct 1", "https://example.com/stream")
        val httpsStream = Station("https://example.com/stream", "Direct 2", "https://example.com/stream")
        val httpStream = Station("http://example.com/stream", "Direct 3", "http://example.com/stream")
        val customUriStream = Station("custom://stream/rock", "Direct 4", "custom://stream/rock")

        assertNull(buildPlayStationCommand(directStreamPrefix, esp32Target))
        assertNull(buildPlayStationCommand(httpsStream, esp32Target))
        assertNull(buildPlayStationCommand(httpStream, esp32Target))
        assertNull(buildPlayStationCommand(customUriStream, esp32Target))
    }

    @Test
    fun buildPlayStationCommand_rejectsTargetWithoutCatalogStationSupport() {
        assertNull(buildPlayStationCommand(catalogStation, directStreamOnlyTarget))
    }

    @Test
    fun checkDevicePlaySupport_validatesUsableTargetAndCapabilities() {
        val validState = TargetDirectoryState.Available(
            targets = listOf(esp32Target),
            selectedTargetId = esp32Target.id,
            grantedScopes = setOf("device.directory.read", "media.control"),
        )
        val support = checkDevicePlaySupport(validState)
        assertTrue(support.supported)
        assertNull(support.reason)
        assertEquals(esp32Target.id, support.target?.id)
    }

    @Test
    fun checkDevicePlaySupport_reportsTargetNotSelected() {
        val noSelection = TargetDirectoryState.Available(
            targets = listOf(esp32Target),
            selectedTargetId = null,
            grantedScopes = setOf("device.directory.read", "media.control"),
        )
        val support = checkDevicePlaySupport(noSelection)
        assertFalse(support.supported)
        assertEquals("Устройство не выбрано", support.reason)
    }

    @Test
    fun checkDevicePlaySupport_reportsOfflineTarget() {
        val offlineTarget = esp32Target.copy(presence = TargetPresence.Offline)
        val offlineState = TargetDirectoryState.Available(
            targets = listOf(offlineTarget),
            selectedTargetId = offlineTarget.id,
            grantedScopes = setOf("device.directory.read", "media.control"),
        )
        val support = checkDevicePlaySupport(offlineState)
        assertFalse(support.supported)
        assertEquals("Устройство offline", support.reason)
    }

    @Test
    fun checkDevicePlaySupport_reportsStaleTarget() {
        val staleTarget = esp32Target.copy(freshness = TargetFreshness.Stale)
        val staleState = TargetDirectoryState.Available(
            targets = listOf(staleTarget),
            selectedTargetId = staleTarget.id,
            grantedScopes = setOf("device.directory.read", "media.control"),
        )
        val support = checkDevicePlaySupport(staleState)
        assertFalse(support.supported)
        assertEquals("Данные устройства устарели", support.reason)
    }

    @Test
    fun checkDevicePlaySupport_reportsMissingMediaControlScope() {
        val missingScopeState = TargetDirectoryState.Available(
            targets = listOf(esp32Target),
            selectedTargetId = esp32Target.id,
            grantedScopes = setOf("device.directory.read"),
        )
        val support = checkDevicePlaySupport(missingScopeState)
        assertFalse(support.supported)
        assertEquals("Нет разрешения media.control", support.reason)
    }

    @Test
    fun checkDevicePlaySupport_reportsUnsupportedCatalogCapability() {
        val directOnlyState = TargetDirectoryState.Available(
            targets = listOf(directStreamOnlyTarget),
            selectedTargetId = directStreamOnlyTarget.id,
            grantedScopes = setOf("device.directory.read", "media.control"),
        )
        val support = checkDevicePlaySupport(directOnlyState)
        assertFalse(support.supported)
        assertEquals("Устройство не поддерживает каталог станций", support.reason)
    }
}
