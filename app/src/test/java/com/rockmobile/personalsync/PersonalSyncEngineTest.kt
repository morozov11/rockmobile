package com.rockmobile.personalsync

import com.rockmobile.data.personal.Favourite
import com.rockmobile.data.personal.PersonalData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PersonalSyncEngineTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z")

    private fun favourite(id: UUID = UUID.randomUUID(), updatedAt: String = "2026-09-01T00:00:00Z") =
        Favourite(id.toString(), "rock-a", "2026-09-01T00:00:00Z", updatedAt, "A")

    private fun response(revision: Long, favouriteRecords: List<FavouriteRecordDto> = emptyList(), historyRecords: List<HistoryRecordDto> = emptyList()) =
        PersonalSyncResponseDto(revision, "2026-09-28T12:00:00Z", CollectionRecordsDto(favouriteRecords), CollectionRecordsDto(historyRecords))

    private class FakeChannel(private val scripted: List<Any>) : PersonalSyncChannel {
        val requests = mutableListOf<PersonalSyncRequestDto>()
        var refreshes = 0
        var sessionDeviceId: String? = "device-1"
        private val queue = ArrayDeque(scripted)
        override suspend fun deviceId(): String? = sessionDeviceId
        override suspend fun send(request: PersonalSyncRequestDto): PersonalSyncResponseDto {
            requests += request
            return when (val next = queue.removeFirst()) {
                is PersonalSyncResponseDto -> next
                is PersonalSyncFailure -> throw next
                else -> error("script exhausted")
            }
        }
        override suspend fun refreshSession() { refreshes++ }
    }

    @Test fun firstSyncPushesEverythingAndPullsTheSnapshot() = runTest {
        val local = favourite()
        val snapshotRecord = FavouriteRecordDto(UUID.randomUUID().toString(), "rock-z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z")
        val channel = FakeChannel(listOf(response(revision = 5, favouriteRecords = listOf(snapshotRecord))))
        val outcome = syncOnce(channel, PersonalData(profileId = "profile", favourites = listOf(local)), PersonalSyncState(deviceId = "device-1", profileId = "profile"), now)
        val request = channel.requests.single()
        assertEquals(null, request.sinceRevision)
        assertEquals(listOf(local.recordId), request.favourites?.upserts?.map { it.recordId })
        assertEquals(1, outcome.pushedFavourites)
        assertEquals(5L, outcome.serverRevision)
        assertEquals(listOf(snapshotRecord), outcome.favouriteRecords)
    }

    @Test fun quietCycleIsOnePullOnlyRequestWithTheCursor() = runTest {
        val local = favourite()
        val state = PersonalSyncState(deviceId = "device-1", profileId = "profile", serverRevision = 7, favourites = mapOf(local.recordId to local))
        val channel = FakeChannel(listOf(response(revision = 8)))
        val outcome = syncOnce(channel, PersonalData(profileId = "profile", favourites = listOf(local)), state, now)
        val request = channel.requests.single()
        assertEquals(7L, request.sinceRevision)
        assertEquals(null, request.favourites)
        assertEquals(null, request.history)
        assertEquals(1, outcome.batches)
        assertEquals(0, outcome.pushedFavourites)
        assertEquals(0, outcome.pushedHistory)
    }

    @Test fun chunkedPushThreadsEachResponseCursorIntoTheNextRequest() = runTest {
        val favourites = (0..SYNC_BATCH_LIMIT).map { favourite() }
        val channel = FakeChannel(listOf(response(revision = 10), response(revision = 11)))
        val outcome = syncOnce(channel, PersonalData(profileId = "profile", favourites = favourites), PersonalSyncState(deviceId = "device-1", profileId = "profile"), now)
        assertEquals(2, channel.requests.size)
        assertEquals(null, channel.requests[0].sinceRevision)
        assertEquals(10L, channel.requests[1].sinceRevision)
        assertTrue(channel.requests.all { it.favourites != null && it.favourites.upserts.size <= SYNC_BATCH_LIMIT })
        assertEquals(SYNC_BATCH_LIMIT + 1, channel.requests.sumOf { it.favourites?.upserts?.size ?: 0 })
        assertEquals(2, outcome.batches)
        assertEquals(11L, outcome.serverRevision)
    }

    @Test fun unauthorizedRefreshesTheSessionOnceAndResendsTheSameRequest() = runTest {
        val local = favourite()
        val success = response(revision = 6)
        val channel = FakeChannel(listOf(PersonalSyncFailure.Unauthorized, success))
        val outcome = syncOnce(channel, PersonalData(profileId = "profile", favourites = listOf(local)), PersonalSyncState(deviceId = "device-1", profileId = "profile"), now)
        assertEquals(1, channel.refreshes)
        assertEquals(2, channel.requests.size)
        assertEquals(channel.requests[0], channel.requests[1])
        assertEquals(6L, outcome.serverRevision)
    }

    @Test fun persistentUnauthorizedFailsTheCycleAfterOneRenewal() = runTest {
        val channel = FakeChannel(listOf(PersonalSyncFailure.Unauthorized, PersonalSyncFailure.Unauthorized))
        val thrown = runCatching { syncOnce(channel, PersonalData(profileId = "profile"), PersonalSyncState(deviceId = "device-1", profileId = "profile"), now) }.exceptionOrNull()
        assertEquals(PersonalSyncFailure.Unauthorized, thrown)
        assertEquals(1, channel.refreshes)
        assertEquals(2, channel.requests.size)
    }

    @Test fun rateLimitAndRejectionSurfaceAsTypedFailures() = runTest {
        for (failure in listOf(PersonalSyncFailure.RateLimited, PersonalSyncFailure.Unavailable, PersonalSyncFailure.Rejected("favourites.upserts"))) {
            val channel = FakeChannel(listOf(failure, failure))
            val thrown = runCatching { syncOnce(channel, PersonalData(profileId = "profile"), PersonalSyncState(deviceId = "device-1", profileId = "profile"), now) }.exceptionOrNull()
            assertEquals(failure, thrown)
        }
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(60_000L, syncRetryBackoffMs(0))
        assertEquals(120_000L, syncRetryBackoffMs(1))
        assertEquals(240_000L, syncRetryBackoffMs(2))
        assertEquals(960_000L, syncRetryBackoffMs(9))
    }
}
