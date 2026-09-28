package com.rockmobile.personalsync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Typed wire boundary for `POST /api/v1/sync` (RockServer OpenAPI 0.6.0, RM-012-A). */
internal object PersonalSyncJson {
    val codec = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false; encodeDefaults = true }
}

@Serializable
internal data class PersonalSyncRequestDto(
    /** Absent (or zero) asks the server for the full account snapshot. */
    @SerialName("since_revision") val sinceRevision: Long? = null,
    val favourites: FavouriteChangesDto? = null,
    val history: HistoryChangesDto? = null,
)

@Serializable
internal data class FavouriteChangesDto(
    val upserts: List<FavouritePushDto>,
    val deletes: List<RecordDeleteDto>,
)

@Serializable
internal data class HistoryChangesDto(
    val upserts: List<HistoryPushDto>,
    val deletes: List<RecordDeleteDto>,
)

@Serializable
internal data class FavouritePushDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("added_at") val addedAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
internal data class HistoryPushDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("last_played_at") val lastPlayedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("play_duration_ms") val playDurationMs: Long? = null,
    val metadata: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
internal data class RecordDeleteDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
internal data class PersonalSyncResponseDto(
    @SerialName("server_revision") val serverRevision: Long,
    @SerialName("server_time") val serverTime: String,
    val favourites: CollectionRecordsDto<FavouriteRecordDto>,
    val history: CollectionRecordsDto<HistoryRecordDto>,
)

@Serializable
internal data class CollectionRecordsDto<T>(val records: List<T>)

/** Tombstoned records keep their last known fields; `deletedAt` presence means delete locally. */
@Serializable
internal data class FavouriteRecordDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("station_id") val stationId: String? = null,
    @SerialName("added_at") val addedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
internal data class HistoryRecordDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("station_id") val stationId: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("last_played_at") val lastPlayedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("play_duration_ms") val playDurationMs: Long? = null,
    val metadata: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)
