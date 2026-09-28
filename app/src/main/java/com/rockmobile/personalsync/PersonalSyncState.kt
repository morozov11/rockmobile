package com.rockmobile.personalsync

import android.content.Context
import com.rockmobile.data.personal.Favourite
import com.rockmobile.data.personal.HistoryEntry
import com.rockmobile.data.personal.PersonalData
import com.rockmobile.data.personal.favouriteFromRecord
import com.rockmobile.data.personal.historyFromRecord
import com.rockmobile.data.personal.instant
import com.rockmobile.data.personal.uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Server-side batch cap per collection (OpenAPI `max_batch_items_per_collection`). */
internal const val SYNC_BATCH_LIMIT = 300

/**
 * Per-device sync state: the last applied `server_revision` cursor plus the exact record
 * versions the server has acknowledged. Diffing the live profile against this base yields
 * the next push batch. A fresh state means a full snapshot pull plus a full local push.
 */
internal data class PersonalSyncState(
    val deviceId: String? = null,
    val profileId: String? = null,
    val serverRevision: Long = 0,
    val favourites: Map<String, Favourite> = emptyMap(),
    val history: Map<String, HistoryEntry> = emptyMap(),
)

internal data class PersonalSyncChanges(
    val favouriteUpserts: List<FavouritePushDto>,
    val favouriteDeletes: List<RecordDeleteDto>,
    val historyUpserts: List<HistoryPushDto>,
    val historyDeletes: List<RecordDeleteDto>,
)

internal data class SyncBatch(val favourites: FavouriteChangesDto?, val history: HistoryChangesDto?)

internal fun toFavouritePush(favourite: Favourite) = FavouritePushDto(
    recordId = favourite.recordId,
    stationId = favourite.stationId,
    addedAt = favourite.addedAt,
    updatedAt = favourite.updatedAt,
)

internal fun toHistoryPush(entry: HistoryEntry) = HistoryPushDto(
    recordId = entry.recordId,
    stationId = entry.stationId,
    startedAt = entry.startedAt,
    lastPlayedAt = entry.lastPlayedAt,
    endedAt = entry.endedAt,
    playDurationMs = entry.playDurationMs,
    metadata = historyMetadataJson(entry.lastKnownName, entry.catalogVersion, entry.source),
    updatedAt = entry.updatedAt,
)

/** Wire metadata projection of a history entry; keys mirror `historyMetadataFromJson`. */
internal fun historyMetadataJson(lastKnownName: String?, catalogVersion: String?, source: String?): JsonObject? {
    val metadata = buildJsonObject {
        lastKnownName?.let { put("lastKnownName", it) }
        catalogVersion?.let { put("catalogVersion", it) }
        source?.let { put("source", it) }
    }
    return metadata.takeIf { it.size > 0 }
}

private fun sameFavouriteWire(base: Favourite, live: Favourite) =
    base.recordId == live.recordId && base.stationId == live.stationId && base.addedAt == live.addedAt && base.updatedAt == live.updatedAt

private fun sameHistoryWire(base: HistoryEntry, live: HistoryEntry) =
    base.recordId == live.recordId && base.stationId == live.stationId && base.startedAt == live.startedAt &&
        base.lastPlayedAt == live.lastPlayedAt && base.updatedAt == live.updatedAt && base.endedAt == live.endedAt &&
        base.playDurationMs == live.playDurationMs &&
        historyMetadataJson(base.lastKnownName, base.catalogVersion, base.source) == historyMetadataJson(live.lastKnownName, live.catalogVersion, live.source)

/**
 * A tombstone must beat the newest version this device ever observed for the record — including
 * versions authored by other devices' clocks — so it is stamped one instant after the
 * acknowledged `updatedAt`, or now, whichever is later.
 */
internal fun tombstoneUpdatedAt(baseUpdatedAt: String, now: Instant): String {
    val newerThanBase = runCatching { instant(baseUpdatedAt).plusNanos(1) }.getOrDefault(now)
    return maxOf(newerThanBase, now).toString()
}

/** Local changes the server has not acknowledged yet. */
internal fun diffSyncChanges(state: PersonalSyncState, profile: PersonalData, now: Instant): PersonalSyncChanges {
    val favouriteUpserts = profile.favourites
        .filterNot { live -> state.favourites[live.recordId]?.let { sameFavouriteWire(it, live) } == true }
        .map(::toFavouritePush)
    val favouriteDeletes = state.favourites
        .filterKeys { id -> profile.favourites.none { it.recordId == id } }
        .map { (id, base) -> RecordDeleteDto(id, tombstoneUpdatedAt(base.updatedAt, now)) }
    val historyUpserts = profile.history
        .filterNot { live -> state.history[live.recordId]?.let { sameHistoryWire(it, live) } == true }
        .map(::toHistoryPush)
    val historyDeletes = state.history
        .filterKeys { id -> profile.history.none { it.recordId == id } }
        .map { (id, base) -> RecordDeleteDto(id, tombstoneUpdatedAt(base.updatedAt, now)) }
    return PersonalSyncChanges(favouriteUpserts, favouriteDeletes, historyUpserts, historyDeletes)
}

/**
 * Splits one pending push into server-legal batches of at most 300 upserts and 300 deletes per
 * collection. The engine threads each response's cursor into the next batch's `since_revision`.
 */
internal fun splitSyncBatches(changes: PersonalSyncChanges): List<SyncBatch> {
    val favouriteUpserts = changes.favouriteUpserts.chunked(SYNC_BATCH_LIMIT)
    val favouriteDeletes = changes.favouriteDeletes.chunked(SYNC_BATCH_LIMIT)
    val historyUpserts = changes.historyUpserts.chunked(SYNC_BATCH_LIMIT)
    val historyDeletes = changes.historyDeletes.chunked(SYNC_BATCH_LIMIT)
    val favouriteBatchCount = maxOf(favouriteUpserts.size, favouriteDeletes.size)
    val historyBatchCount = maxOf(historyUpserts.size, historyDeletes.size)
    return (0 until maxOf(favouriteBatchCount, historyBatchCount)).map { index ->
        SyncBatch(
            favourites = if (index < favouriteBatchCount) {
                FavouriteChangesDto(favouriteUpserts.getOrNull(index).orEmpty(), favouriteDeletes.getOrNull(index).orEmpty())
                    .takeIf { it.upserts.isNotEmpty() || it.deletes.isNotEmpty() }
            } else null,
            history = if (index < historyBatchCount) {
                HistoryChangesDto(historyUpserts.getOrNull(index).orEmpty(), historyDeletes.getOrNull(index).orEmpty())
                    .takeIf { it.upserts.isNotEmpty() || it.deletes.isNotEmpty() }
            } else null,
        )
    }
}

internal fun syncRequest(batch: SyncBatch, sinceRevision: Long) = PersonalSyncRequestDto(
    sinceRevision = sinceRevision.takeIf { it > 0 },
    favourites = batch.favourites,
    history = batch.history,
)

internal fun pullOnlyRequest(serverRevision: Long) = PersonalSyncRequestDto(sinceRevision = serverRevision.takeIf { it > 0 })

/**
 * The acknowledged base after a fully applied cycle: every record of the pushed snapshot the
 * server has seen, overlaid with the response's adjudicated versions (echoes and deltas).
 * Records created locally mid-cycle are absent, so the next diff still pushes them; records
 * deleted locally mid-cycle stay at their snapshot version, so the next diff tombstones them.
 */
internal fun nextSyncState(snapshot: PersonalData, outcome: PersonalSyncOutcome, deviceId: String, profileId: String): PersonalSyncState {
    val favourites = LinkedHashMap<String, Favourite>()
    snapshot.favourites.forEach { favourites[it.recordId] = it }
    outcome.favouriteRecords.forEach { record ->
        val incoming = favouriteFromRecord(record)
        when {
            record.deletedAt != null -> favourites.remove(record.recordId)
            incoming != null -> favourites[record.recordId] = incoming
            else -> Unit
        }
    }
    val history = LinkedHashMap<String, HistoryEntry>()
    snapshot.history.forEach { history[it.recordId] = it }
    outcome.historyRecords.forEach { record ->
        val incoming = historyFromRecord(record)
        when {
            record.deletedAt != null -> history.remove(record.recordId)
            incoming != null -> history[record.recordId] = incoming
            else -> Unit
        }
    }
    return PersonalSyncState(deviceId, profileId, outcome.serverRevision, favourites, history)
}

internal interface PersonalSyncStateStore {
    /** Returns the durable state for this device/profile pair, resetting it on any mismatch or corruption. */
    fun load(deviceId: String, profileId: String): PersonalSyncState
    fun save(state: PersonalSyncState)
}

/** Returns the stored state only when it belongs to this exact device/profile pair. */
internal fun storedStateFor(raw: String?, deviceId: String, profileId: String): PersonalSyncState? =
    raw?.let(::decodeSyncState)?.takeIf { it.deviceId == deviceId && it.profileId == profileId }

internal fun encodeSyncState(state: PersonalSyncState): String = JSONObject().apply {
    put("schemaVersion", 1)
    put("deviceId", state.deviceId)
    put("profileId", state.profileId)
    put("serverRevision", state.serverRevision)
    put("favourites", JSONArray(state.favourites.values.map { JSONObject()
        .put("recordId", it.recordId).put("stationId", it.stationId).put("addedAt", it.addedAt).put("updatedAt", it.updatedAt) }))
    put("history", JSONArray(state.history.values.map { JSONObject()
        .put("recordId", it.recordId).put("stationId", it.stationId).put("startedAt", it.startedAt).put("lastPlayedAt", it.lastPlayedAt).put("updatedAt", it.updatedAt)
        .put("endedAt", it.endedAt).put("playDurationMs", it.playDurationMs)
        .put("metadata", JSONObject().put("lastKnownName", it.lastKnownName).put("catalogVersion", it.catalogVersion).put("source", it.source)) }))
}.toString()

/** A missing, corrupt, or forward-incompatible value is a fresh state, never a sync failure. */
internal fun decodeSyncState(raw: String): PersonalSyncState? = try {
    val root = JSONObject(raw)
    require(root.optInt("schemaVersion", -1) == 1)
    val favourites = root.optJSONArray("favourites")?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()
        .map { Favourite(uuid(it.getString("recordId")), it.getString("stationId"), it.getString("addedAt").also(::instant), it.getString("updatedAt").also(::instant), null) }
    val history = root.optJSONArray("history")?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()
        .map { item ->
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            HistoryEntry(
                uuid(item.getString("recordId")), item.getString("stationId"),
                item.getString("startedAt").also(::instant), item.getString("lastPlayedAt").also(::instant), item.getString("updatedAt").also(::instant),
                item.optString("endedAt").takeIf(String::isNotEmpty)?.also(::instant),
                if (item.has("playDurationMs") && !item.isNull("playDurationMs")) item.getLong("playDurationMs") else null,
                metadata.optString("lastKnownName").takeIf(String::isNotEmpty),
                metadata.optString("catalogVersion").takeIf(String::isNotEmpty),
                metadata.optString("source").takeIf(String::isNotEmpty),
            )
        }
    PersonalSyncState(
        deviceId = root.optString("deviceId").takeIf(String::isNotEmpty),
        profileId = root.optString("profileId").takeIf(String::isNotEmpty),
        serverRevision = root.optLong("serverRevision", 0),
        favourites = favourites.associateBy(Favourite::recordId),
        history = history.associateBy(HistoryEntry::recordId),
    )
} catch (_: Exception) { null }

/**
 * SharedPreferences-backed per-device cursor. A fresh pairing or a recreated profile must not
 * inherit a foreign cursor, so any mismatch resets to a full-snapshot state before the cycle runs.
 */
internal class SharedPrefsPersonalSyncStateStore(context: Context) : PersonalSyncStateStore {
    private val prefs = context.getSharedPreferences("rockmobile_personal_data", Context.MODE_PRIVATE)

    override fun load(deviceId: String, profileId: String): PersonalSyncState {
        storedStateFor(prefs.getString(KEY, null), deviceId, profileId)?.let { return it }
        val fresh = PersonalSyncState(deviceId = deviceId, profileId = profileId)
        save(fresh)
        return fresh
    }

    override fun save(state: PersonalSyncState) {
        check(prefs.edit().putString(KEY, encodeSyncState(state)).commit()) { "Personal sync state write failed" }
    }

    private companion object { const val KEY = "personal_sync_state.v1" }
}
