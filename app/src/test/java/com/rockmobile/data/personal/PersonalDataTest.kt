package com.rockmobile.data.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalDataTest {
    private val active = LocalCatalogIndex(setOf("rock-a", "rock-b"), legacyIds = mapOf("rockmobile:old" to "rock-a"), merged = mapOf("merged" to "next", "next" to "rock-a", "to-split" to "split"), splits = mapOf("split" to listOf("rock-a", "rock-b")), removed = setOf("gone"), catalogVersion = "fixture")
    private fun favourite(id: String, at: String = "2026-08-25T00:00:00Z", updated: String = at, name: String? = id) = Favourite(java.util.UUID.randomUUID().toString(), id, at, updated, name)
    private fun history(id: String, at: String = "2026-08-25T00:00:00Z") = HistoryEntry(java.util.UUID.randomUUID().toString(), id, at, at, at, at, 0, id, source = "bundled")

    @Test fun stableIdSurvivesUrlChangeBecauseOnlyIdIsStored() { assertEquals("rock-a", resolvePersonalData(PersonalData(favourites = listOf(favourite("rock-a"))), active, Instant.parse("2026-08-25T00:01:00Z")).favourites.single().stationId) }
    @Test fun legacyAndMergeAreIdempotent() { val once = resolvePersonalData(PersonalData(favourites = listOf(favourite("rockmobile:old"), favourite("merged"))), active, Instant.parse("2026-08-25T00:01:00Z")); assertEquals(once.favourites, resolvePersonalData(once, active, Instant.parse("2026-08-25T00:01:00Z")).favourites); assertEquals(listOf("rock-a"), once.favourites.map { it.stationId }) }
    @Test fun unresolvableIdsStayInTheProfileInsteadOfBeingQuarantined() { val result = resolvePersonalData(PersonalData(favourites = listOf(favourite("to-split"), favourite("gone"), favourite("unknown"))), active, Instant.parse("2026-08-25T00:01:00Z")); assertEquals(setOf("to-split", "gone", "unknown"), result.favourites.map { it.stationId }.toSet()); assertTrue(result.unresolved.isEmpty()); val reread = read(encode(result)); assertEquals(setOf("to-split", "gone", "unknown"), reread.data.favourites.map { it.stationId }.toSet()) }
    @Test fun quarantinedReferencesReturnAsLiveRecordsWithOriginalIds() {
        val favouriteRef = UnresolvedReference(java.util.UUID.randomUUID().toString(), "favourite", "rb-vanya", "2026-09-18T14:42:37Z", "missing", emptyList(), "Радио Ваня", "fixture")
        val historyRef = UnresolvedReference(java.util.UUID.randomUUID().toString(), "history", "rb-old", "2026-09-04T20:33:52Z", "missing", emptyList(), "Old Station", "fixture")
        val result = resolvePersonalData(PersonalData(unresolved = listOf(favouriteRef, historyRef)), active, Instant.parse("2026-09-28T12:00:00Z"))
        assertEquals(listOf("rb-vanya"), result.favourites.map { it.stationId })
        assertEquals(favouriteRef.referenceId, result.favourites.single().recordId)
        assertEquals("Радио Ваня", result.favourites.single().lastKnownName)
        assertEquals("2026-09-18T14:42:37Z", result.favourites.single().updatedAt)
        assertEquals(listOf("rb-old"), result.history.map { it.stationId })
        assertEquals("Old Station", result.history.single().lastKnownName)
        assertTrue(result.unresolved.isEmpty())
    }
    @Test fun dedupKeepsEarliestRecordAndLatestMetadata() { val first = favourite("rock-a", "2026-08-25T00:00:00Z", name = "old"); val latest = favourite("rock-a", "2026-08-25T00:01:00Z", "2026-08-25T00:02:00Z", "new"); val result = resolvePersonalData(PersonalData(favourites = listOf(latest, first)), active, Instant.parse("2026-08-25T00:03:00Z")).favourites.single(); assertEquals(first.recordId, result.recordId); assertEquals("new", result.lastKnownName); assertEquals(latest.updatedAt, result.updatedAt) }
    @Test fun historyOrderingAndRetentionAreDeterministic() { val old = history("rock-a", "2026-01-01T00:00:00Z"); val fresh = history("rock-b", "2026-08-25T00:00:00Z"); val result = resolvePersonalData(PersonalData(history = listOf(old, fresh)), active, Instant.parse("2026-08-25T00:01:00Z")); assertEquals(listOf("rock-b"), result.history.map { it.stationId }) }
    @Test fun historyUpdatedAtBackfillsFromLastPlayedAtInV1Migration() {
        val raw = """{"schemaVersion":1,"profileId":"0d99e676-6c2f-4b7b-9b1f-1f2f3d4d5e6f","createdAt":"2026-08-20T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z",
            "favourites":[{"recordId":"81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f","stationId":"rock-a","addedAt":"2026-08-20T00:00:00Z","updatedAt":"2026-08-20T00:00:00Z","metadata":{"lastKnownName":"A"}}],
            "playbackHistory":[{"recordId":"92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f","stationId":"rock-b","startedAt":"2026-08-24T10:00:00Z","lastPlayedAt":"2026-08-24T10:05:00Z","endedAt":"2026-08-24T10:05:00Z","playDurationMs":300000,"metadata":{"lastKnownName":"B"}}],
            "unresolvedReferences":[],"metadata":{"lastPlayedStationId":"rock-b","launchCount":3}}"""
        val loaded = read(raw)
        assertEquals(raw, loaded.migrationSource)
        assertEquals("2026-08-24T10:05:00Z", loaded.data.history.single().updatedAt)
        val encoded = read(encode(loaded.data))
        assertEquals(null, encoded.migrationSource)
        assertEquals("2026-08-24T10:05:00Z", encoded.data.history.single().updatedAt)
        assertTrue(encode(loaded.data).contains("\"schemaVersion\":2"))
    }
    @Test fun legacyEpochMigrationBackfillsHistoryUpdatedAt() {
        val addedMs = Instant.parse("2026-08-23T00:00:00Z").toEpochMilli()
        val startedMs = Instant.parse("2026-08-24T10:00:00Z").toEpochMilli()
        val playedMs = Instant.parse("2026-08-24T10:05:00Z").toEpochMilli()
        val raw = """{"schemaVersion":1,"favourites":[{"recordId":"81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f","stationId":"rock-a","addedAt":$addedMs,"updatedAt":$addedMs,"name":"A"}],
            "history":[{"recordId":"92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f","stationId":"rock-b","startedAt":$startedMs,"lastPlayedAt":$playedMs,"endedAt":$playedMs,"duration":300000,"name":"B"}],
            "unresolved":[],"lastPlayedStationId":"rock-b","launchCount":2}"""
        val loaded = read(raw)
        assertEquals(raw, loaded.migrationSource)
        assertEquals(instant("2026-08-24T10:05:00Z"), instant(loaded.data.history.single().updatedAt))
    }
    @Test fun v2ProfileRoundTripsHistoryUpdatedAt() {
        val entry = history("rock-a", "2026-08-25T00:00:00Z")
        val withStamp = entry.copy(updatedAt = "2026-08-25T00:07:30Z")
        val loaded = read(encode(PersonalData(profileId = "0d99e676-6c2f-4b7b-9b1f-1f2f3d4d5e6f", history = listOf(withStamp))))
        assertEquals(null, loaded.migrationSource)
        assertEquals("2026-08-25T00:07:30Z", loaded.data.history.single().updatedAt)
    }
    @Test fun stationIdRewriteAdvancesUpdatedAtSoServerLwwCanAdoptIt() {
        val favourite = favourite("rockmobile:old", "2026-08-25T00:00:00Z")
        val entry = history("merged", "2026-08-25T00:00:00Z")
        val result = resolvePersonalData(PersonalData(favourites = listOf(favourite), history = listOf(entry)), active, Instant.parse("2026-08-25T00:01:00Z"))
        assertEquals("2026-08-25T00:01:00Z", result.favourites.single().updatedAt)
        assertEquals("2026-08-25T00:01:00Z", result.history.single().updatedAt)
        val again = resolvePersonalData(result, active, Instant.parse("2026-08-25T00:02:00Z"))
        assertEquals(result.favourites, again.favourites)
        assertEquals(result.history, again.history)
    }
}
