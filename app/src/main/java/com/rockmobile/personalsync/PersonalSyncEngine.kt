package com.rockmobile.personalsync

import com.rockmobile.account.NativeSessionManager
import com.rockmobile.data.api.ApiError
import com.rockmobile.data.personal.PersonalData
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connection-level seam: engine tests drive a scripted fake, production uses the HTTP channel. */
internal interface PersonalSyncChannel {
    /** Device identity of the stored pairing, or null when this phone has no session. */
    suspend fun deviceId(): String?
    suspend fun send(request: PersonalSyncRequestDto): PersonalSyncResponseDto
    /** Refreshes the native device session after an unexpected 401. */
    suspend fun refreshSession()
}

internal data class PersonalSyncOutcome(
    /** Every record the caller must apply (delta plus echoes of pushed records, including tombstones). */
    val favouriteRecords: List<FavouriteRecordDto>,
    val historyRecords: List<HistoryRecordDto>,
    /** Cursor to persist only after the records are durably applied. */
    val serverRevision: Long,
    val batches: Int,
    val pushedFavourites: Int,
    val pushedHistory: Int,
)

/** Exponential backoff for rate-limited/unavailable cycles: 1, 2, 4, … minutes, capped at 16. */
internal fun syncRetryBackoffMs(failedAttempts: Int): Long = 60_000L shl failedAttempts.coerceAtMost(4)

/**
 * One push+pull sync cycle (RM-012-C): diff the profile against the last acknowledged base,
 * deliver batch chunks with a single 401-driven session renewal per request, and thread each
 * response's cursor into the next chunk so later chunks pull deltas, not snapshots.
 */
internal suspend fun syncOnce(
    channel: PersonalSyncChannel,
    profile: PersonalData,
    state: PersonalSyncState,
    now: Instant,
): PersonalSyncOutcome {
    val changes = diffSyncChanges(state, profile, now)
    val pushedFavourites = changes.favouriteUpserts.size + changes.favouriteDeletes.size
    val pushedHistory = changes.historyUpserts.size + changes.historyDeletes.size
    val batches = splitSyncBatches(changes)
    val favouriteRecords = mutableListOf<FavouriteRecordDto>()
    val historyRecords = mutableListOf<HistoryRecordDto>()
    var cursor = state.serverRevision
    if (batches.isEmpty()) {
        val response = deliver(channel, pullOnlyRequest(cursor))
        return PersonalSyncOutcome(
            response.favourites.records,
            response.history.records,
            response.serverRevision,
            batches = 1,
            pushedFavourites = 0,
            pushedHistory = 0,
        )
    }
    for (batch in batches) {
        val response = deliver(channel, syncRequest(batch, cursor))
        cursor = response.serverRevision
        favouriteRecords += response.favourites.records
        historyRecords += response.history.records
    }
    return PersonalSyncOutcome(favouriteRecords, historyRecords, cursor, batches.size, pushedFavourites, pushedHistory)
}

/** Sends one request, refreshing the native session exactly once when the server answers 401. */
private suspend fun deliver(channel: PersonalSyncChannel, request: PersonalSyncRequestDto): PersonalSyncResponseDto =
    try {
        channel.send(request)
    } catch (unauthorized: PersonalSyncFailure.Unauthorized) {
        channel.refreshSession()
        channel.send(request)
    }

/** Production channel over the shared native device session; tokens stay in memory only. */
internal class RockserverSyncChannel(
    private val api: PersonalSyncApi,
    private val sessions: NativeSessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonalSyncChannel {
    private var accessToken: String? = null
    private var tokenDeviceId: String? = null

    override suspend fun deviceId(): String? = sessions.storedDeviceId()

    override suspend fun send(request: PersonalSyncRequestDto): PersonalSyncResponseDto {
        val token = currentToken() ?: throw PersonalSyncFailure.Unauthorized
        return withContext(ioDispatcher) { api.sync(token, request) }
    }

    override suspend fun refreshSession() {
        val expected = tokenDeviceId ?: sessions.storedDeviceId() ?: throw PersonalSyncFailure.Unauthorized
        try {
            val renewed = sessions.renew(expected)
            accessToken = renewed.accessToken
            tokenDeviceId = renewed.deviceId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw if (error.isSessionRejected()) PersonalSyncFailure.Unauthorized else PersonalSyncFailure.Unavailable
        }
    }

    private suspend fun currentToken(): String? {
        val cachedDevice = sessions.storedDeviceId() ?: return null
        if (accessToken != null && tokenDeviceId == cachedDevice) return accessToken
        val credentials = sessions.currentSession() ?: return null
        accessToken = credentials.accessToken
        tokenDeviceId = credentials.deviceId
        return accessToken
    }
}

private fun Exception.isSessionRejected(): Boolean =
    this is ApiError && (statusCode == 401 || code == "device_credential_invalid") ||
        this is IllegalStateException
