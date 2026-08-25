package com.rockmobile.data.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalDataTest {
    private val active = LocalCatalogIndex(setOf("rock-a", "rock-b"), legacyIds = mapOf("rockmobile:old" to "rock-a"), merged = mapOf("merged" to "next", "next" to "rock-a", "to-split" to "split"), splits = mapOf("split" to listOf("rock-a", "rock-b")), removed = setOf("gone"), catalogVersion = "fixture")
    private fun favourite(id: String, at: String = "2026-08-25T00:00:00Z", updated: String = at, name: String? = id) = Favourite(java.util.UUID.randomUUID().toString(), id, at, updated, name)
    private fun history(id: String, at: String = "2026-08-25T00:00:00Z") = HistoryEntry(java.util.UUID.randomUUID().toString(), id, at, at, at, 0, id, source = "bundled")

    @Test fun stableIdSurvivesUrlChangeBecauseOnlyIdIsStored() { assertEquals("rock-a", resolvePersonalData(PersonalData(favourites = listOf(favourite("rock-a"))), active, Instant.parse("2026-08-25T00:01:00Z")).favourites.single().stationId) }
    @Test fun legacyAndMergeAreIdempotent() { val once = resolvePersonalData(PersonalData(favourites = listOf(favourite("rockmobile:old"), favourite("merged"))), active, Instant.parse("2026-08-25T00:01:00Z")); assertEquals(once.favourites, resolvePersonalData(once, active, Instant.parse("2026-08-25T00:01:00Z")).favourites); assertEquals(listOf("rock-a"), once.favourites.map { it.stationId }) }
    @Test fun splitRemovedAndMissingAreQuarantinedWithoutAutomaticTransfer() { val result = resolvePersonalData(PersonalData(favourites = listOf(favourite("to-split"), favourite("gone"), favourite("unknown"))), active, Instant.parse("2026-08-25T00:01:00Z")); assertTrue(result.favourites.isEmpty()); assertEquals(setOf("split", "removed", "missing"), result.unresolved.map { it.reason }.toSet()); assertEquals(listOf("rock-a", "rock-b"), result.unresolved.single { it.reason == "split" }.candidates) }
    @Test fun dedupKeepsEarliestRecordAndLatestMetadata() { val first = favourite("rock-a", "2026-08-25T00:00:00Z", name = "old"); val latest = favourite("rock-a", "2026-08-25T00:01:00Z", "2026-08-25T00:02:00Z", "new"); val result = resolvePersonalData(PersonalData(favourites = listOf(latest, first)), active, Instant.parse("2026-08-25T00:03:00Z")).favourites.single(); assertEquals(first.recordId, result.recordId); assertEquals("new", result.lastKnownName); assertEquals(latest.updatedAt, result.updatedAt) }
    @Test fun historyOrderingAndRetentionAreDeterministic() { val old = history("rock-a", "2026-01-01T00:00:00Z"); val fresh = history("rock-b", "2026-08-25T00:00:00Z"); val result = resolvePersonalData(PersonalData(history = listOf(old, fresh)), active, Instant.parse("2026-08-25T00:01:00Z")); assertEquals(listOf("rock-b"), result.history.map { it.stationId }) }
}
