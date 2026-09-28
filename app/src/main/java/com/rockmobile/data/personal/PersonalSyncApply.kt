package com.rockmobile.data.personal

import com.rockmobile.personalsync.FavouriteRecordDto
import com.rockmobile.personalsync.HistoryRecordDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

internal data class SyncApplyResult(val data: PersonalData, val changed: Int)

internal fun historyMetadataFromJson(metadata: JsonObject?): Triple<String?, String?, String?> = Triple(
    metadata?.stringKey("lastKnownName"),
    metadata?.stringKey("catalogVersion"),
    metadata?.stringKey("source"),
)

private fun JsonObject.stringKey(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotBlank() }
    ?.content

/** A validated, non-tombstoned favourite record, or null for malformed input. */
internal fun favouriteFromRecord(record: FavouriteRecordDto): Favourite? {
    if (record.deletedAt != null) return null
    val stationId = record.stationId?.takeIf(::validId) ?: return null
    return try {
        Favourite(uuid(record.recordId), stationId, record.addedAt.also { Instant.parse(it) }, record.updatedAt.also { Instant.parse(it) }, null)
    } catch (_: Exception) { null }
}

/** A validated, non-tombstoned history record, or null for malformed input. */
internal fun historyFromRecord(record: HistoryRecordDto): HistoryEntry? {
    if (record.deletedAt != null) return null
    val stationId = record.stationId?.takeIf(::validId) ?: return null
    return try {
        val (name, version, source) = historyMetadataFromJson(record.metadata)
        HistoryEntry(
            recordId = uuid(record.recordId),
            stationId = stationId,
            startedAt = record.startedAt.also { Instant.parse(it) },
            lastPlayedAt = record.lastPlayedAt.also { Instant.parse(it) },
            updatedAt = record.updatedAt.also { Instant.parse(it) },
            endedAt = record.endedAt?.also { Instant.parse(it) },
            playDurationMs = record.playDurationMs?.takeIf { it >= 0 },
            lastKnownName = name,
            catalogVersion = version,
            source = source,
        )
    } catch (_: Exception) { null }
}

/**
 * Applies incoming RM-012 sync records with the RM-007-A lifecycle rules: `deleted_at` removes the
 * record, last-writer-wins replaces only a strictly newer `updated_at` (ties keep the local record),
 * and malformed records are skipped rather than corrupting the offline profile.
 */
internal fun applyIncomingSyncRecords(data: PersonalData, favourites: List<FavouriteRecordDto>, history: List<HistoryRecordDto>): SyncApplyResult {
    var changed = 0
    val nextFavourites = data.favourites.toMutableList()
    for (record in favourites) {
        if (record.deletedAt != null) {
            if (nextFavourites.removeAll { it.recordId == record.recordId }) changed++
            continue
        }
        val incoming = favouriteFromRecord(record) ?: continue
        val index = nextFavourites.indexOfFirst { it.recordId == incoming.recordId }
        if (index < 0) {
            nextFavourites += incoming
            changed++
        } else if (Instant.parse(nextFavourites[index].updatedAt) < Instant.parse(incoming.updatedAt)) {
            // Favourite display metadata is a local presentation concern: the server contract carries none.
            nextFavourites[index] = nextFavourites[index].copy(stationId = incoming.stationId, addedAt = incoming.addedAt, updatedAt = incoming.updatedAt)
            changed++
        }
    }
    val nextHistory = data.history.toMutableList()
    for (record in history) {
        if (record.deletedAt != null) {
            if (nextHistory.removeAll { it.recordId == record.recordId }) changed++
            continue
        }
        val incoming = historyFromRecord(record) ?: continue
        val index = nextHistory.indexOfFirst { it.recordId == incoming.recordId }
        if (index < 0) {
            nextHistory += incoming
            changed++
        } else if (Instant.parse(nextHistory[index].updatedAt) < Instant.parse(incoming.updatedAt)) {
            val local = nextHistory[index]
            nextHistory[index] = incoming.copy(
                lastKnownName = incoming.lastKnownName ?: local.lastKnownName,
                catalogVersion = incoming.catalogVersion ?: local.catalogVersion,
                source = incoming.source ?: local.source,
            )
            changed++
        }
    }
    return SyncApplyResult(data.copy(favourites = nextFavourites, history = nextHistory), changed)
}
