package com.rockmobile.devicecontrol

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import java.io.Closeable
import java.io.IOException
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
        val repository = TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(transport)) { "https://server.test" }, socket, MemorySelections(), 100)
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

    @Test fun missingDirectoryScope_isNonBlockingUnavailableState() = runTest {
        val socket = FakeSockets()
        val transport = QueueTransport(emptyList(), responseCode = 403)
        val repository = TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(transport)) { "https://server.test" }, socket, MemorySelections())
        repository.start(this, session())
        runCurrent()
        val state = repository.state.value as TargetDirectoryState.Unavailable
        assertTrue(state.scopeMissing)
        assertEquals(0, socket.connectCalls)
    }

    private fun repository(socket: FakeSockets, selections: MemorySelections, vararg snapshots: DirectoryDto): TargetDirectoryRepository =
        TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(QueueTransport(snapshots.toList()))) { "https://server.test" }, socket, selections, 100)

    private fun session() = ControllerSession("owner", "controller", "a".repeat(16))
    private fun directory(revision: Long, vararg devices: DirectoryEntryDto) = DirectoryDto(1, "2026-09-02T12:00:00Z", revision, listOf("device.directory.read"), devices.toList())
    private fun rockCast(known: List<String> = listOf("media.playback")) = player("rockcast", known = known, name = "Living room RockCast")
    private fun player(id: String, presence: String = "online", freshness: String = "fresh", known: List<String> = emptyList(), name: String = id) = DirectoryEntryDto(
        id, name, "rockcast", listOf("player"), CapabilitiesDto(1, known.map { CapabilityDto(it, 1) }),
        PresenceDto(presence, null, if (presence == "offline") "transport_lost" else null), FreshnessDto(freshness),
    )

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
        override fun connect(baseUrl: String, accessToken: String, listener: DirectorySocketListener): Closeable {
            connectCalls++; this.listener = listener
            return Closeable { }
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
