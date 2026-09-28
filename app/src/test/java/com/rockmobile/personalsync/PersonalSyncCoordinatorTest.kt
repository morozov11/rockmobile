package com.rockmobile.personalsync

import com.rockmobile.data.personal.Favourite
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.data.personal.applyIncomingSyncRecords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSyncCoordinatorTest {
    private val profileId = "0d99e676-6c2f-4b7b-9b1f-1f2f3d4d5e6f"

    private class FakeChannel(val scripted: MutableList<Any>) : PersonalSyncChannel {
        val requests = mutableListOf<PersonalSyncRequestDto>()
        var refreshes = 0
        var sessionDeviceId: String? = "device-1"
        override suspend fun deviceId(): String? = sessionDeviceId
        override suspend fun send(request: PersonalSyncRequestDto): PersonalSyncResponseDto {
            requests += request
            return when (val next = scripted.removeAt(0)) {
                is PersonalSyncResponseDto -> next
                is PersonalSyncFailure -> throw next
                else -> error("script exhausted")
            }
        }
        override suspend fun refreshSession() { refreshes++ }
    }

    private class FakeStateStore : PersonalSyncStateStore {
        var stored: String? = null
        var saved: PersonalSyncState? = null
        override fun load(deviceId: String, profileId: String): PersonalSyncState =
            storedStateFor(stored, deviceId, profileId) ?: PersonalSyncState(deviceId = deviceId, profileId = profileId).also(::save)

        override fun save(state: PersonalSyncState) {
            saved = state
            stored = encodeSyncState(state)
        }
    }

    private class FakeProfile(initial: PersonalData) {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
        var applyError: RuntimeException? = null
        val applier: (List<FavouriteRecordDto>, List<HistoryRecordDto>) -> Int = { favourites, history ->
            applyError?.let { throw it }
            val result = applyIncomingSyncRecords(flow.value, favourites, history)
            flow.value = result.data
            result.changed
        }
    }

    private fun response(revision: Long, favouriteRecords: List<FavouriteRecordDto> = emptyList(), historyRecords: List<HistoryRecordDto> = emptyList()) =
        PersonalSyncResponseDto(revision, "2026-09-28T12:00:00Z", CollectionRecordsDto(favouriteRecords), CollectionRecordsDto(historyRecords))

    private fun favouriteRecord(recordId: String, station: String = "rock-z", updatedAt: String = "2026-09-02T00:00:00Z") =
        FavouriteRecordDto(recordId, station, "2026-09-01T00:00:00Z", updatedAt)

    private fun favourite(station: String = "rock-a", updatedAt: String = "2026-09-01T00:00:00Z") =
        Favourite(UUID.randomUUID().toString(), station, "2026-09-01T00:00:00Z", updatedAt, station)

    private fun coordinator(
        channel: FakeChannel,
        profile: FakeProfile,
        stateStore: FakeStateStore,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): PersonalSyncCoordinator = PersonalSyncCoordinator(
        channel = channel,
        profileState = profile.flow,
        applyRecords = profile.applier,
        stateStore = stateStore,
        ioDispatcher = StandardTestDispatcher(scheduler),
        nowMs = { scheduler.currentTime },
        editDebounceMs = 10_000,
        pullIntervalMs = 300_000,
    )

    @Test fun startRunsTheInitialCycleAppliesTheSnapshotAndPersistsTheCursor() = runTest {
        val channel = FakeChannel(mutableListOf(response(revision = 5, favouriteRecords = listOf(favouriteRecord("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f")))))
        val profile = FakeProfile(PersonalData(profileId = profileId))
        val stateStore = FakeStateStore()
        val coordinator = coordinator(channel, profile, stateStore, testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(1, channel.requests.size)
        assertEquals(5L, stateStore.saved?.serverRevision)
        assertEquals("device-1", stateStore.saved?.deviceId)
        assertEquals(listOf("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f"), profile.flow.value.favourites.map { it.recordId })
        val ok = coordinator.status.value as PersonalSyncStatus.Ok
        assertEquals(1, ok.appliedRecords)
        assertEquals("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", stateStore.saved?.favourites?.keys?.single())
    }

    @Test fun localEditsGetAPushAfterTheDebounce() = runTest {
        val remote = favouriteRecord("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f")
        val addedLocally = favourite(station = "rock-local", updatedAt = "2026-09-20T00:00:00Z")
        val channel = FakeChannel(mutableListOf(response(revision = 5, favouriteRecords = listOf(remote))))
        val profile = FakeProfile(PersonalData(profileId = profileId))
        val coordinator = coordinator(channel, profile, FakeStateStore(), testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(1, channel.requests.size)
        profile.flow.value = profile.flow.value.copy(favourites = profile.flow.value.favourites + addedLocally)
        channel.scripted += response(revision = 6, favouriteRecords = listOf(favouriteRecord(addedLocally.recordId, station = "rock-local", updatedAt = "2026-09-20T00:00:00Z")))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, channel.requests.size)
        assertEquals(listOf(addedLocally.recordId), channel.requests[1].favourites?.upserts?.map { it.recordId })
        assertEquals(5L, channel.requests[1].sinceRevision)
    }

    @Test fun periodicPullKeepsConvergingWithoutLocalChanges() = runTest {
        val channel = FakeChannel(mutableListOf(response(revision = 1)))
        val profile = FakeProfile(PersonalData(profileId = profileId))
        val coordinator = coordinator(channel, profile, FakeStateStore(), testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(1, channel.requests.size)
        channel.scripted += response(revision = 2, historyRecords = listOf(HistoryRecordDto("92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "rock-a", "2026-09-05T10:00:00Z", "2026-09-05T10:05:00Z", null, 300_000, null, "2026-09-05T10:05:00Z")))
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(2, channel.requests.size)
        assertEquals(1L, channel.requests[1].sinceRevision)
        assertEquals("92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", profile.flow.value.history.single().recordId)
    }

    @Test fun rateLimitedCycleBacksOffAndRecoversOnALaterTrigger() = runTest {
        val channel = FakeChannel(mutableListOf(PersonalSyncFailure.RateLimited))
        val profile = FakeProfile(PersonalData(profileId = profileId))
        val stateStore = FakeStateStore()
        val coordinator = coordinator(channel, profile, stateStore, testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(1, channel.requests.size)
        assertTrue(coordinator.status.value is PersonalSyncStatus.Error)
        coordinator.requestSync()
        runCurrent()
        assertEquals("backoff suppresses immediate retries", 1, channel.requests.size)
        channel.scripted += response(revision = 3)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("periodic trigger fires once the backoff has expired", 2, channel.requests.size)
        assertTrue(coordinator.status.value is PersonalSyncStatus.Ok)
        assertEquals(3L, stateStore.saved?.serverRevision)
    }

    @Test fun missingSessionKeepsSyncOffWithoutAnyRequest() = runTest {
        val channel = FakeChannel(mutableListOf()).apply { sessionDeviceId = null }
        val coordinator = coordinator(channel, FakeProfile(PersonalData(profileId = profileId)), FakeStateStore(), testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(0, channel.requests.size)
        assertEquals(PersonalSyncStatus.Off, coordinator.status.value)
    }

    @Test fun newPairingResetsTheCursorAndStartsFromTheFullSnapshot() = runTest {
        val previous = PersonalSyncState(deviceId = "device-1", profileId = profileId, serverRevision = 42)
        val stateStore = FakeStateStore().apply { stored = encodeSyncState(previous) }
        val local = favourite(station = "rock-local")
        val channel = FakeChannel(mutableListOf(response(revision = 9))).apply { sessionDeviceId = "device-2" }
        val profile = FakeProfile(PersonalData(profileId = profileId, favourites = listOf(local)))
        val coordinator = coordinator(channel, profile, stateStore, testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        val request = channel.requests.single()
        assertEquals("a foreign cursor must not leak into the first cycle", null, request.sinceRevision)
        assertEquals(listOf(local.recordId), request.favourites?.upserts?.map { it.recordId })
        assertEquals("device-2", stateStore.saved?.deviceId)
        assertEquals(9L, stateStore.saved?.serverRevision)
    }

    @Test fun cursorIsPersistedOnlyAfterTheRecordsAreApplied() = runTest {
        val channel = FakeChannel(mutableListOf(response(revision = 5, favouriteRecords = listOf(favouriteRecord("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f")))))
        val profile = FakeProfile(PersonalData(profileId = profileId)).apply { applyError = IllegalStateException("unwritable") }
        val stateStore = FakeStateStore()
        val coordinator = coordinator(channel, profile, stateStore, testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertTrue(coordinator.status.value is PersonalSyncStatus.Error)
        assertEquals("a failed apply must not advance the cursor", 0L, stateStore.saved?.serverRevision)
    }

    @Test fun deletedLocalRecordsTravelAsTombstones() = runTest {
        val kept = favourite(station = "rock-a", updatedAt = "2026-09-01T00:00:00Z")
        val removed = favourite(station = "rock-b", updatedAt = "2026-09-01T00:00:00Z")
        val channel = FakeChannel(mutableListOf(response(revision = 4)))
        val profile = FakeProfile(PersonalData(profileId = profileId, favourites = listOf(kept, removed)))
        val stateStore = FakeStateStore()
        val coordinator = coordinator(channel, profile, stateStore, testScheduler)
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(setOf(kept.recordId, removed.recordId), stateStore.saved?.favourites?.keys?.toSet())
        profile.flow.value = profile.flow.value.copy(favourites = listOf(kept))
        channel.scripted += response(revision = 5)
        advanceTimeBy(10_000)
        runCurrent()
        val delete = channel.requests[1].favourites?.deletes?.single()
        assertEquals(removed.recordId, delete?.recordId)
        assertTrue(delete != null && com.rockmobile.data.personal.instant(delete.updatedAt) > com.rockmobile.data.personal.instant(removed.updatedAt))
    }
}
