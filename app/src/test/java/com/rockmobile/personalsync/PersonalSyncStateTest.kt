package com.rockmobile.personalsync

import com.rockmobile.data.personal.Favourite
import com.rockmobile.data.personal.HistoryEntry
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.data.personal.instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PersonalSyncStateTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z")
    private fun favourite(id: UUID = UUID.randomUUID(), station: String = "rock-a", addedAt: String = "2026-09-01T00:00:00Z", updatedAt: String = "2026-09-02T00:00:00Z") =
        Favourite(id.toString(), station, addedAt, updatedAt, station)

    private fun history(id: UUID = UUID.randomUUID(), station: String = "rock-a", startedAt: String = "2026-09-05T10:00:00Z", lastPlayedAt: String = "2026-09-05T10:05:00Z", updatedAt: String = lastPlayedAt) =
        HistoryEntry(id.toString(), station, startedAt, lastPlayedAt, updatedAt, lastPlayedAt, 300_000, station, source = "catalog")

    private fun profile(favourites: List<Favourite> = emptyList(), history: List<HistoryEntry> = emptyList(), profileId: String = "0d99e676-6c2f-4b7b-9b1f-1f2f3d4d5e6f") =
        PersonalData(profileId = profileId, favourites = favourites, history = history)

    private fun outcome(
        revision: Long,
        favouriteRecords: List<FavouriteRecordDto> = emptyList(),
        historyRecords: List<HistoryRecordDto> = emptyList(),
    ) = PersonalSyncOutcome(favouriteRecords, historyRecords, revision, batches = 1, pushedFavourites = 0, pushedHistory = 0)

    @Test fun freshStateDiffPushesEverythingAndAsksForTheFullSnapshot() {
        val live = profile(favourites = listOf(favourite()), history = listOf(history()))
        val changes = diffSyncChanges(PersonalSyncState(deviceId = "device-1", profileId = live.profileId), live, now)
        assertEquals(1, changes.favouriteUpserts.size)
        assertEquals(1, changes.historyUpserts.size)
        assertTrue(changes.favouriteDeletes.isEmpty())
        assertTrue(changes.historyDeletes.isEmpty())
        val batches = splitSyncBatches(changes)
        assertEquals(1, batches.size)
        val request = syncRequest(batches.single(), sinceRevision = 0)
        assertNull(request.sinceRevision)
    }

    @Test fun diffReportsOnlyChangedAndDeletedRecords() {
        val kept = favourite()
        val changed = favourite(station = "rock-b")
        val removed = favourite(station = "rock-c")
        val state = PersonalSyncState(
            deviceId = "device-1", profileId = "profile", serverRevision = 7,
            favourites = mapOf(kept.recordId to kept, changed.recordId to changed.copy(updatedAt = "2026-09-01T00:00:00Z"), removed.recordId to removed),
        )
        val live = profile(favourites = listOf(kept, changed))
        val changes = diffSyncChanges(state, live, now)
        assertEquals(listOf(changed.recordId), changes.favouriteUpserts.map { it.recordId })
        val delete = changes.favouriteDeletes.single()
        assertEquals(removed.recordId, delete.recordId)
        assertTrue(instant(delete.updatedAt) > instant(removed.updatedAt))
        val request = syncRequest(splitSyncBatches(changes).single(), sinceRevision = 7)
        assertEquals(7L, request.sinceRevision)
    }

    @Test fun tombstoneBeatsAFutureDatedBaseVersion() {
        val stamp = tombstoneUpdatedAt("2999-01-01T00:00:00Z", now)
        assertTrue(instant(stamp) > Instant.parse("2999-01-01T00:00:00Z"))
        assertTrue(instant(tombstoneUpdatedAt("2000-01-01T00:00:00Z", now)) >= now)
    }

    @Test fun oversizedPushSplitsIntoServerLegalBatches() {
        val upserts = (0..SYNC_BATCH_LIMIT).map { favourite() }
        val deletes = (0 until SYNC_BATCH_LIMIT * 2).map { RecordDeleteDto(UUID.randomUUID().toString(), "2026-09-01T00:00:00Z") }
        val state = PersonalSyncState(
            deviceId = "device-1", profileId = "profile",
            favourites = upserts.associate { it.recordId to it.copy(updatedAt = "2026-08-01T00:00:00Z") } +
                deletes.associate { it.recordId to favourite(UUID.fromString(it.recordId)) },
        )
        val live = profile(favourites = upserts)
        val batches = splitSyncBatches(diffSyncChanges(state, live, now))
        assertEquals(2, batches.size)
        batches.forEach { batch ->
            assertTrue(batch.favourites != null || batch.history != null)
            batch.favourites?.let {
                assertTrue(it.upserts.size <= SYNC_BATCH_LIMIT)
                assertTrue(it.deletes.size <= SYNC_BATCH_LIMIT)
            }
        }
        assertEquals(SYNC_BATCH_LIMIT + 1, batches.sumOf { it.favourites?.upserts?.size ?: 0 })
        assertEquals(SYNC_BATCH_LIMIT * 2, batches.sumOf { it.favourites?.deletes?.size ?: 0 })
    }

    @Test fun acknowledgedBaseCoversSnapshotAndEchoesButNotMidFlightCreations() {
        val pushed = favourite(updatedAt = "2026-09-01T00:00:00Z")
        val unchanged = favourite(station = "rock-b", updatedAt = "2026-09-01T00:00:00Z")
        val deletedMidCycle = favourite(station = "rock-c", updatedAt = "2026-09-01T00:00:00Z")
        val snapshot = profile(favourites = listOf(pushed, unchanged, deletedMidCycle), history = listOf(history()))
        val echo = favouriteRecord(pushed.recordId, station = "rock-z", updatedAt = "2026-09-02T00:00:00Z")
        val next = nextSyncState(snapshot, outcome(revision = 9, favouriteRecords = listOf(echo)), deviceId = "device-1", profileId = snapshot.profileId)
        assertEquals(9L, next.serverRevision)
        // The echo's adjudicated version replaces the pushed snapshot version.
        assertEquals("rock-z", next.favourites[pushed.recordId]?.stationId)
        assertEquals("2026-09-01T00:00:00Z", next.favourites[unchanged.recordId]?.updatedAt)
        // A mid-cycle deletion stays acknowledged at its snapshot version, so the next diff tombstones it.
        assertTrue(pushed.recordId in next.favourites && unchanged.recordId in next.favourites && deletedMidCycle.recordId in next.favourites)
        val createdAfterSnapshot = favourite(station = "rock-new")
        val pushedAsEchoed = pushed.copy(stationId = "rock-z", updatedAt = "2026-09-02T00:00:00Z")
        val live = snapshot.copy(favourites = snapshot.favourites - deletedMidCycle - pushed + pushedAsEchoed + createdAfterSnapshot)
        val changes = diffSyncChanges(next, live, now)
        assertEquals(listOf(createdAfterSnapshot.recordId), changes.favouriteUpserts.map { it.recordId })
        assertEquals(listOf(deletedMidCycle.recordId), changes.favouriteDeletes.map { it.recordId })
    }

    @Test fun tombstoneEchoDropsTheRecordFromTheAcknowledgedBase() {
        val pushed = favourite()
        val snapshot = profile(favourites = listOf(pushed))
        val tombstoneEcho = favouriteRecord(pushed.recordId, updatedAt = "2026-09-02T00:00:00Z", deletedAt = "2026-09-02T00:00:00Z")
        val next = nextSyncState(snapshot, outcome(revision = 3, favouriteRecords = listOf(tombstoneEcho)), deviceId = "device-1", profileId = snapshot.profileId)
        assertTrue(pushed.recordId !in next.favourites)
        val changes = diffSyncChanges(next, profile(), now)
        assertTrue(changes.favouriteUpserts.isEmpty())
        assertTrue(changes.favouriteDeletes.isEmpty())
    }

    @Test fun historyMetadataProjectionKeepsDiffQuietForIdenticalEntries() {
        val entry = history()
        val state = PersonalSyncState(deviceId = "device-1", profileId = "profile", history = mapOf(entry.recordId to entry))
        val changes = diffSyncChanges(state, profile(history = listOf(entry)), now)
        assertTrue(changes.historyUpserts.isEmpty())
        val renamed = entry.copy(lastKnownName = "Renamed")
        val renamedChanges = diffSyncChanges(state, profile(history = listOf(renamed)), now)
        assertEquals(1, renamedChanges.historyUpserts.size)
    }

    @Test fun stateRoundTripsThroughItsCodec() {
        val entry = history()
        val state = PersonalSyncState(
            deviceId = "device-1", profileId = "profile", serverRevision = 17,
            favourites = mapOf("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f" to favourite(UUID.fromString("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f"))),
            history = mapOf(entry.recordId to entry),
        )
        // Favourite display metadata never rides the wire, so the base keeps wire fields only.
        val expected = state.copy(favourites = state.favourites.mapValues { it.value.copy(lastKnownName = null) })
        assertEquals(expected, decodeSyncState(encodeSyncState(state)))
        assertNull(decodeSyncState("{not json"))
        assertNull(decodeSyncState("""{"schemaVersion":2}"""))
    }

    @Test fun storedStateIsUsableOnlyForTheSameDeviceAndProfile() {
        val raw = encodeSyncState(PersonalSyncState(deviceId = "device-1", profileId = "profile-a", serverRevision = 42))
        assertEquals(42L, storedStateFor(raw, "device-1", "profile-a")?.serverRevision)
        assertNull(storedStateFor(raw, "device-2", "profile-a"))
        assertNull(storedStateFor(raw, "device-1", "profile-b"))
        assertNull(storedStateFor(null, "device-1", "profile-a"))
        assertNull(storedStateFor("garbage", "device-1", "profile-a"))
    }

    private fun favouriteRecord(recordId: String, station: String = "rock-a", updatedAt: String, deletedAt: String? = null) =
        FavouriteRecordDto(recordId, station, "2026-09-01T00:00:00Z", updatedAt, deletedAt)

    @Test fun pullOnlyRequestCarriesTheCursorOnlyWhenPositive() {
        assertEquals("""{"since_revision":7}""", PersonalSyncJson.codec.encodeToString(PersonalSyncRequestDto.serializer(), pullOnlyRequest(7)))
        assertEquals("{}", PersonalSyncJson.codec.encodeToString(PersonalSyncRequestDto.serializer(), pullOnlyRequest(0)))
        assertEquals(0, splitSyncBatches(PersonalSyncChanges(emptyList(), emptyList(), emptyList(), emptyList())).size)
    }
}
