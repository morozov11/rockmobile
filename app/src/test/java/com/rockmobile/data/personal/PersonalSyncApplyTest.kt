package com.rockmobile.data.personal

import com.rockmobile.personalsync.FavouriteRecordDto
import com.rockmobile.personalsync.HistoryRecordDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PersonalSyncApplyTest {
    private fun favourite(recordId: String = UUID.randomUUID().toString(), station: String = "rock-a", addedAt: String = "2026-09-01T00:00:00Z", updatedAt: String = "2026-09-02T00:00:00Z", name: String? = "A") =
        Favourite(recordId, station, addedAt, updatedAt, name)

    private fun history(recordId: String = UUID.randomUUID().toString(), station: String = "rock-a", startedAt: String = "2026-09-05T10:00:00Z", lastPlayedAt: String = "2026-09-05T10:05:00Z", updatedAt: String = lastPlayedAt, name: String? = "A") =
        HistoryEntry(recordId, station, startedAt, lastPlayedAt, updatedAt, "2026-09-05T10:05:00Z", 300_000, name, source = "catalog")

    private fun favouriteRecord(recordId: String, station: String = "rock-a", updatedAt: String, addedAt: String = "2026-09-01T00:00:00Z", deletedAt: String? = null) =
        FavouriteRecordDto(recordId, station, addedAt, updatedAt, deletedAt)

    private fun historyRecord(recordId: String, station: String = "rock-a", updatedAt: String, startedAt: String = "2026-09-05T10:00:00Z", lastPlayedAt: String = "2026-09-05T10:05:00Z", deletedAt: String? = null, metadata: kotlinx.serialization.json.JsonObject? = null) =
        HistoryRecordDto(recordId, station, startedAt, lastPlayedAt, null, 300_000, metadata, updatedAt, deletedAt)

    @Test fun lastWriterWinsReplacesOnlyStrictlyNewerVersions() {
        val local = favourite(updatedAt = "2026-09-02T00:00:00Z")
        val older = applyIncomingSyncRecords(PersonalData(favourites = listOf(local)), listOf(favouriteRecord(local.recordId, updatedAt = "2026-09-01T00:00:00Z")), emptyList())
        assertEquals(0, older.changed)
        assertEquals("2026-09-02T00:00:00Z", older.data.favourites.single().updatedAt)
        val tie = applyIncomingSyncRecords(PersonalData(favourites = listOf(local)), listOf(favouriteRecord(local.recordId, station = "rock-b", updatedAt = "2026-09-02T00:00:00Z")), emptyList())
        assertEquals(0, tie.changed)
        assertEquals("rock-a", tie.data.favourites.single().stationId)
        val newer = applyIncomingSyncRecords(PersonalData(favourites = listOf(local)), listOf(favouriteRecord(local.recordId, station = "rock-b", updatedAt = "2026-09-03T00:00:00Z")), emptyList())
        assertEquals(1, newer.changed)
        assertEquals("rock-b", newer.data.favourites.single().stationId)
        assertEquals("2026-09-03T00:00:00Z", newer.data.favourites.single().updatedAt)
    }

    @Test fun tombstonesRemoveLiveRecordsAndIgnoreMissingOnes() {
        val kept = favourite()
        val removed = favourite(station = "rock-b")
        val result = applyIncomingSyncRecords(
            PersonalData(favourites = listOf(kept, removed), history = listOf(history())),
            listOf(favouriteRecord(removed.recordId, updatedAt = "2026-09-04T00:00:00Z", deletedAt = "2026-09-04T00:00:00Z"), favouriteRecord(UUID.randomUUID().toString(), updatedAt = "2026-09-04T00:00:00Z", deletedAt = "2026-09-04T00:00:00Z")),
            listOf(historyRecord(UUID.randomUUID().toString(), updatedAt = "2026-09-06T00:00:00Z", deletedAt = "2026-09-06T00:00:00Z")),
        )
        assertEquals(1, result.changed)
        assertEquals(listOf(kept.recordId), result.data.favourites.map { it.recordId })
        assertEquals(1, result.data.history.size)
    }

    @Test fun applyingTheSameResponseTwiceIsIdempotent() {
        val incoming = listOf(favouriteRecord("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", updatedAt = "2026-09-02T00:00:00Z"))
        val first = applyIncomingSyncRecords(PersonalData(), incoming, emptyList())
        assertEquals(1, first.changed)
        val second = applyIncomingSyncRecords(first.data, incoming, emptyList())
        assertEquals(0, second.changed)
        assertEquals(first.data.favourites, second.data.favourites)
    }

    @Test fun malformedRecordsAreSkippedAtTheBoundary() {
        val result = applyIncomingSyncRecords(
            PersonalData(),
            listOf(
                favouriteRecord("not-a-uuid", updatedAt = "2026-09-02T00:00:00Z"),
                favouriteRecord(UUID.randomUUID().toString(), station = "Not A Valid Id!", updatedAt = "2026-09-02T00:00:00Z"),
                favouriteRecord(UUID.randomUUID().toString(), updatedAt = "not-a-timestamp"),
            ),
            listOf(historyRecord("also-not-a-uuid", updatedAt = "2026-09-05T10:05:00Z")),
        )
        assertEquals(0, result.changed)
        assertTrue(result.data.favourites.isEmpty())
        assertTrue(result.data.history.isEmpty())
    }

    @Test fun favouriteReplacementKeepsLocalPresentationMetadata() {
        val local = favourite(name = "Known Name")
        val result = applyIncomingSyncRecords(PersonalData(favourites = listOf(local)), listOf(favouriteRecord(local.recordId, updatedAt = "2026-09-09T00:00:00Z")), emptyList())
        assertEquals("Known Name", result.data.favourites.single().lastKnownName)
    }

    @Test fun historyMetadataTravelsBothWaysAndMissingKeysKeepLocalValues() {
        val metadata = buildJsonObject {
            put("lastKnownName", "Server Name")
            put("source", "rockcast")
            put("unknownKey", "ignored")
        }
        val inserted = applyIncomingSyncRecords(PersonalData(), emptyList(), listOf(historyRecord("92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", updatedAt = "2026-09-05T10:05:00Z", metadata = metadata)))
        val entry = inserted.data.history.single()
        assertEquals("Server Name", entry.lastKnownName)
        assertEquals("rockcast", entry.source)
        val newerWithoutMetadata = listOf(historyRecord(entry.recordId, updatedAt = "2026-09-05T11:00:00Z"))
        val replaced = applyIncomingSyncRecords(inserted.data, emptyList(), newerWithoutMetadata)
        assertEquals("Server Name", replaced.data.history.single().lastKnownName)
        assertEquals("2026-09-05T11:00:00Z", replaced.data.history.single().updatedAt)
    }

    @Test fun losingEchoLeavesLocalWinnerUntouched() {
        val localWinner = favourite(updatedAt = "2026-09-10T00:00:00Z")
        val echoOfOlderPush = favouriteRecord(localWinner.recordId, station = "rock-b", updatedAt = "2026-09-09T00:00:00Z")
        val result = applyIncomingSyncRecords(PersonalData(favourites = listOf(localWinner)), listOf(echoOfOlderPush), emptyList())
        assertEquals(0, result.changed)
        assertEquals("rock-a", result.data.favourites.single().stationId)
    }

    @Test fun sortedDeduplicatedOutcomeAppliesPredictably() {
        val id = "81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f"
        val result = applyIncomingSyncRecords(
            PersonalData(),
            listOf(favouriteRecord(id, station = "rock-a", updatedAt = "2026-09-01T00:00:00Z"), favouriteRecord(id, station = "rock-b", updatedAt = "2026-09-02T00:00:00Z")),
            emptyList(),
        )
        val applied = result.data.favourites.single()
        assertEquals("rock-b", applied.stationId)
        assertEquals("2026-09-02T00:00:00Z", applied.updatedAt)
        assertTrue(Instant.parse(applied.addedAt) < Instant.parse(applied.updatedAt))
    }
}
