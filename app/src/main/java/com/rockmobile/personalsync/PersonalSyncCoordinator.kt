package com.rockmobile.personalsync

import android.util.Log
import com.rockmobile.data.personal.PersonalData
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sync status for the account screen; carries no record data. */
sealed interface PersonalSyncStatus {
    data object Off : PersonalSyncStatus
    data object Idle : PersonalSyncStatus
    data object Syncing : PersonalSyncStatus
    data class Ok(
        val lastSyncAtMs: Long,
        val appliedRecords: Int,
        val pushedFavourites: Int,
        val pushedHistory: Int,
    ) : PersonalSyncStatus
    data class Error(val retryScheduled: Boolean) : PersonalSyncStatus
}

/** Safe sync diagnostics for adb logcat; phases and counters only. */
internal object PersonalSyncLog {
    private const val TAG = "RockMobileSync"

    fun cycleOk(outcome: PersonalSyncOutcome, applied: Int) = runCatching {
        Log.i(TAG, "cycle ok pushedFavourites=${outcome.pushedFavourites} pushedHistory=${outcome.pushedHistory} applied=$applied batches=${outcome.batches} cursor=${outcome.serverRevision}")
    }

    fun cycleFailed(failure: PersonalSyncFailure, retryInMs: Long) = runCatching {
        Log.w(TAG, "cycle failed (${failure.message}) retryInMs=$retryInMs")
    }
}

/**
 * Event-driven RM-012-C orchestration (no WorkManager): a startup cycle, a ~10 s debounced push
 * after local edits, a ~5 min periodic pull, and a cycle on foreground return. Cycles are
 * serialized, never run on the main thread, and never block the UI.
 */
internal class PersonalSyncCoordinator(
    private val channel: PersonalSyncChannel,
    private val profileState: StateFlow<PersonalData>,
    private val applyRecords: (favourites: List<FavouriteRecordDto>, history: List<HistoryRecordDto>) -> Int,
    private val stateStore: PersonalSyncStateStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val editDebounceMs: Long = 10_000,
    private val pullIntervalMs: Long = 300_000,
) {
    private val _status = MutableStateFlow<PersonalSyncStatus>(PersonalSyncStatus.Off)
    val status: StateFlow<PersonalSyncStatus> = _status.asStateFlow()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private var scope: CoroutineScope? = null
    private var jobs: List<Job> = emptyList()
    private var backoffAttempts = 0
    private var retryNotBeforeMs = 0L

    fun start(scope: CoroutineScope) {
        if (this.scope == scope && jobs.any { it.isActive }) return
        stop()
        this.scope = scope
        jobs = listOf(
            scope.launch { for (request in syncRequests) runCycle() },
            scope.launch {
                if (channel.deviceId() != null && _status.value == PersonalSyncStatus.Off) _status.value = PersonalSyncStatus.Idle
            },
            scope.launch {
                profileState.drop(1).collectLatest {
                    delay(editDebounceMs)
                    requestSync()
                }
            },
            scope.launch {
                while (isActive) {
                    delay(pullIntervalMs)
                    requestSync()
                }
            },
        )
        requestSync()
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        scope = null
    }

    /** Foreground/account-dialog trigger; conflated with any pending startup or debounce request. */
    fun requestSync() {
        syncRequests.trySend(Unit)
    }

    private suspend fun runCycle() {
        if (nowMs() < retryNotBeforeMs) return
        if (channel.deviceId() == null) {
            _status.value = PersonalSyncStatus.Off
            return
        }
        withContext(ioDispatcher) { cycleOnce() }
    }

    private suspend fun cycleOnce() {
        _status.value = PersonalSyncStatus.Syncing
        val deviceId = channel.deviceId() ?: run {
            _status.value = PersonalSyncStatus.Off
            return
        }
        val snapshot = profileState.value
        val state = stateStore.load(deviceId, snapshot.profileId)
        try {
            val outcome = syncOnce(channel, snapshot, state, Instant.ofEpochMilli(nowMs()))
            val applied = applyRecords(outcome.favouriteRecords, outcome.historyRecords)
            // The cursor and acknowledged base are persisted only after the response is durably applied.
            stateStore.save(nextSyncState(snapshot, outcome, deviceId, snapshot.profileId))
            backoffAttempts = 0
            retryNotBeforeMs = 0L
            _status.value = PersonalSyncStatus.Ok(nowMs(), applied, outcome.pushedFavourites, outcome.pushedHistory)
            PersonalSyncLog.cycleOk(outcome, applied)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PersonalSyncFailure) {
            onCycleFailed(failure)
        } catch (error: Exception) {
            onCycleFailed(PersonalSyncFailure.LocalApply(error.javaClass.simpleName))
        }
    }

    private fun onCycleFailed(failure: PersonalSyncFailure) {
        when (failure) {
            PersonalSyncFailure.Unauthorized -> {
                backoffAttempts = 0
                retryNotBeforeMs = 0L
                _status.value = PersonalSyncStatus.Off
            }
            PersonalSyncFailure.RateLimited, PersonalSyncFailure.Unavailable -> {
                val delayMs = syncRetryBackoffMs(backoffAttempts)
                backoffAttempts++
                retryNotBeforeMs = nowMs() + delayMs
                _status.value = PersonalSyncStatus.Error(retryScheduled = true)
            }
            is PersonalSyncFailure.Rejected, is PersonalSyncFailure.Invalid, is PersonalSyncFailure.LocalApply ->
                _status.value = PersonalSyncStatus.Error(retryScheduled = false)
        }
        PersonalSyncLog.cycleFailed(failure, (retryNotBeforeMs - nowMs()).coerceAtLeast(0))
    }
}
