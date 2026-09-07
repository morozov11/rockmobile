package com.rockmobile.devicecontrol

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDirectoryRepositoryTest {
    @Test fun commandFrame_keepsRequiredProtocolDefaults() {
        val frame = commandFrame(DeviceCommandPayloadDto("command", DeviceTargetDto("target"), "2026-09-02T12:00:10Z", RemoteCommand.Play))
        assertTrue(frame.contains("\"protocol_version\":1"))
        assertTrue(frame.contains("\"message_id\":"))
        assertTrue(frame.contains("\"sent_at\":"))
    }

    @Test fun typedSnapshot_mapsRockCastAndSafelyIgnoresUnknownCapabilitiesAndMessages() {
        val snapshot = directory(1, rockCast(known = listOf("media.playback", "future.magic")))
        val message = SnapshotMessageDto(1, "message", "directory.snapshot", "2026-09-02T12:00:00Z", SnapshotPayloadDto("event", snapshot))
        val decoded = decodeDirectoryMessage(DirectoryJson.codec.encodeToString(message)) as DirectoryWireMessage.Snapshot
        val target = decoded.directory.devices.single().toTarget()
        assertTrue(KnownCapability.Playback in target.knownCapabilities)
        assertEquals(1, target.knownCapabilities.size)
        assertEquals(DirectoryWireMessage.IgnoredUnknown, decodeDirectoryMessage(DirectoryJson.codec.encodeToString(UnknownMessageDto(1, "message", "future.event", "2026-09-02T12:00:00Z", IgnoredPayloadDto()))))
    }

    @Test fun explicitSelection_restoresOnlyUsableTargetAndRemovalNeverSubstitutes() = runTest {
        val selections = MemorySelections()
        val socket = FakeSockets()
        val repository = repository(socket, selections, directory(1, rockCast()), directory(2, player("other")))
        repository.start(this, session())
        runCurrent()
        assertEquals(null, (repository.state.value as TargetDirectoryState.Available).selectedTargetId)
        repository.select("rockcast")
        assertEquals("rockcast", selections.value)
        assertEquals("rockcast", (repository.state.value as TargetDirectoryState.Available).selectedTargetId)
        socket.listener!!.onMessage(DirectoryWireMessage.Removed(2, "rockcast", RemovalReason.Removed))
        runCurrent()
        val state = repository.state.value as TargetDirectoryState.Available
        assertEquals(null, state.selectedTargetId)
        assertTrue(state.requiresSelection)
        assertEquals(null, selections.value)
        assertTrue(state.notice!!.contains("Выбранное"))
    }

    @Test fun offlineStaleAndUnknownTargets_areVisibleButCannotBecomeImplicitSelection() = runTest {
        val socket = FakeSockets()
        val repository = repository(socket, MemorySelections(), directory(1,
            player("offline", presence = "offline", freshness = "unknown"),
            player("stale", freshness = "stale"),
            player("unknown", freshness = "unknown"),
        ))
        repository.start(this, session())
        runCurrent()
        val state = repository.state.value as TargetDirectoryState.Available
        assertEquals(null, state.selectedTargetId)
        assertFalse(state.targets.single { it.id == "offline" }.usable)
        assertEquals(TargetFreshness.Stale, state.targets.single { it.id == "stale" }.freshness)
        assertEquals(TargetFreshness.Unknown, state.targets.single { it.id == "unknown" }.freshness)
    }

    @Test fun revisionGap_reloadsSnapshotAndSocketLossReconnectsOnce() = runTest {
        val socket = FakeSockets()
        val transport = QueueTransport(listOf(directory(1, rockCast()), directory(4, player("new"))))
        val repository = TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(transport)) { "https://server.test" }, socket, MemorySelections(), 100, testDispatcher())
        repository.start(this, session())
        runCurrent()
        socket.listener!!.onMessage(DirectoryWireMessage.Upsert(3, player("gap")))
        runCurrent()
        assertEquals(2, transport.getCalls)
        assertEquals(2, socket.connectCalls)
        socket.disconnect()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(3, socket.connectCalls)
        assertEquals(3, transport.getCalls)
    }

    @Test fun missingDirectoryScope_bootstrapsControllerSocket() = runTest {
        val socket = FakeSockets()
        val transport = QueueTransport(emptyList(), responseCode = 403)
        val repository = TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(transport)) { "https://server.test" }, socket, MemorySelections(), ioDispatcher = testDispatcher())
        repository.start(this, session())
        runCurrent()
        assertEquals(TargetDirectoryState.Loading, repository.state.value)
        assertEquals(1, socket.connectCalls)
        socket.listener!!.onMessage(DirectoryWireMessage.Snapshot(directory(1, rockCast())))
        runCurrent()
        assertTrue(repository.state.value is TargetDirectoryState.Available)
    }

    @Test fun knownCapabilities_mapOnlyAdvertisedControls_andUnknownStaysInvisible() {
        val target = player("rockcast", known = listOf("media.playback", "media.volume", "media.chromecast", "media.relay", "future.magic"))
            .copy(capabilities = CapabilitiesDto(1, listOf(
                capability("media.playback"), capability("media.volume"),
                CapabilityDto("media.chromecast", 1, actions = listOf("discover", "connect"), discoveryTtlSeconds = 60),
                CapabilityDto("media.relay", 1, actions = listOf("start", "set_mode"), modes = listOf("local")), CapabilityDto("future.magic", 1),
            )))
            .toTarget()
        assertEquals(setOf(KnownCapability.Playback, KnownCapability.Volume, KnownCapability.Chromecast, KnownCapability.Relay), target.knownCapabilities)
        assertEquals(setOf(PlaybackAction.Play, PlaybackAction.Pause), target.capability<ControlCapability.Playback>()!!.actions)
        assertEquals(setOf("local"), target.capability<ControlCapability.Relay>()!!.modes)
    }

    @Test fun dispatch_isExplicitScopedAndDeduplicated_thenAcceptedIsNotSuccess() = runTest {
        val socket = FakeSockets()
        val snapshot = directory(1, rockCast(known = listOf("media.playback")), scopes = listOf("device.directory.read", "media.control"))
        val repository = repository(socket, MemorySelections(), snapshot)
        repository.start(this, session()); runCurrent()
        assertEquals(null, repository.dispatch(RemoteCommand.Play))
        repository.select("rockcast")
        val id = repository.dispatch(RemoteCommand.Play, java.time.Instant.parse("2026-09-02T12:00:00Z"))!!
        assertEquals(null, repository.dispatch(RemoteCommand.Play, java.time.Instant.parse("2026-09-02T12:00:00Z")))
        assertEquals(1, socket.sent.size)
        assertEquals("rockcast", socket.sent.single().target.deviceId)
        assertEquals(CommandPhase.Pending, repository.commands.value[id]!!.phase)
        socket.listener!!.onMessage(DirectoryWireMessage.CommandAccepted(id)); runCurrent()
        assertEquals(CommandPhase.Accepted, repository.commands.value[id]!!.phase)
        assertFalse(repository.commands.value[id]!!.phase == CommandPhase.Succeeded)
    }

    @Test fun terminalSuccess_waitsForRefreshedDirectory_andRemovalCancels() = runTest {
        val socket = FakeSockets()
        val first = directory(1, rockCast(known = listOf("media.playback")), scopes = listOf("device.directory.read", "media.control"))
        val refreshed = directory(2, rockCast(known = listOf("media.playback")), scopes = listOf("device.directory.read", "media.control"))
        val repository = TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(QueueTransport(listOf(first, refreshed))), { "https://server.test" }), socket, MemorySelections(), ioDispatcher = testDispatcher())
        repository.start(this, session()); runCurrent(); repository.select("rockcast")
        val id = repository.dispatch(RemoteCommand.Play)!!
        socket.listener!!.onMessage(DirectoryWireMessage.CommandResult(id, CommandResultStatus.Succeeded, null, CommandResultOutputDto(stateRevision = 2))); runCurrent()
        assertEquals(CommandPhase.Succeeded, repository.commands.value[id]!!.phase)
        val second = repository.dispatch(RemoteCommand.Play)!!
        socket.listener!!.onMessage(DirectoryWireMessage.Removed(3, "rockcast", RemovalReason.Revoked)); runCurrent()
        assertEquals(CommandPhase.Cancelled, repository.commands.value[second]!!.phase)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(socket: FakeSockets, selections: MemorySelections, vararg snapshots: DirectoryDto): TargetDirectoryRepository =
        TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(QueueTransport(snapshots.toList()))) { "https://server.test" }, socket, selections, 100, testDispatcher())

    private fun kotlinx.coroutines.test.TestScope.testDispatcher(): CoroutineDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private fun session() = ControllerSession("owner", "controller", "a".repeat(16))
    private fun directory(revision: Long, vararg devices: DirectoryEntryDto, scopes: List<String> = listOf("device.directory.read")) = DirectoryDto(1, "2026-09-02T12:00:00Z", revision, scopes, devices.toList())
    private fun rockCast(known: List<String> = listOf("media.playback")) = player("rockcast", known = known, name = "Living room RockCast")
    private fun player(id: String, presence: String = "online", freshness: String = "fresh", known: List<String> = emptyList(), name: String = id) = DirectoryEntryDto(
        id, name, "rockcast", listOf("player"), CapabilitiesDto(1, known.map { capability(it) }),
        PresenceDto(presence, null, if (presence == "offline") "transport_lost" else null), FreshnessDto(freshness),
    )
    private fun capability(name: String) = when (name) {
        "media.playback" -> CapabilityDto(name, 1, actions = listOf("play", "pause"))
        "media.volume" -> CapabilityDto(name, 1, minimum = 0, maximum = 100, step = 5, mute = true)
        else -> CapabilityDto(name, 1)
    }

    private class QueueTransport(private val snapshots: List<DirectoryDto>, private val responseCode: Int = 200) : HttpTransport {
        var getCalls = 0
        override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse = throw IOException("unused")
        override fun get(url: String, bearerToken: String): HttpResponse {
            getCalls++
            if (responseCode != 200) return HttpResponse(responseCode, "")
            return HttpResponse(responseCode, DirectoryJson.codec.encodeToString(snapshots[(getCalls - 1).coerceAtMost(snapshots.lastIndex)]))
        }
    }
    private class FakeSockets : DirectorySocketFactory {
        var connectCalls = 0
        var listener: DirectorySocketListener? = null
        val sent = mutableListOf<DeviceCommandPayloadDto>()
        override fun connect(baseUrl: String, accessToken: String, listener: DirectorySocketListener): DirectorySocketConnection {
            connectCalls++; this.listener = listener
            return object : DirectorySocketConnection {
                override fun send(command: DeviceCommandPayloadDto): Boolean { sent += command; return true }
                override fun close() = Unit
            }
        }
        fun disconnect() { listener?.onClosed(false) }
    }
    private class MemorySelections : TargetSelectionStore {
        var value: String? = null
        override fun load(userId: String, controllerDeviceId: String) = value
        override fun save(userId: String, controllerDeviceId: String, targetId: String) { value = targetId }
        override fun clear(userId: String, controllerDeviceId: String) { value = null }
    }
}
