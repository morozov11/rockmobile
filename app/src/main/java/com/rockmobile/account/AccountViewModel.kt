package com.rockmobile.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rockmobile.data.api.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

private const val DEFAULT_PAIRING_TIMEOUT_MS = 10 * 60 * 1000L
private const val DEFAULT_PAIRING_POLL_MS = 2_000L

internal fun shouldContinuePairing(error: Throwable, nowMs: Long, deadlineMs: Long): Boolean =
    error is ApiError && error.code == "pairing_pending" && nowMs < deadlineMs

internal fun shouldRetryPairingPoll(error: Throwable, nowMs: Long, deadlineMs: Long): Boolean =
    nowMs < deadlineMs && (
        shouldContinuePairing(error, nowMs, deadlineMs) ||
            error is IOException && error !is ApiError
    )

internal fun pairingErrorMessage(error: Throwable, deadlineReached: Boolean = false): String = when {
    deadlineReached -> "Ссылка истекла"
    error is ApiError && error.code == "pairing_rejected" -> "Подключение не подтверждено"
    error is ApiError && error.code == "pairing_expired" -> "Ссылка истекла"
    error is ApiError && error.code == "device_limit_reached" -> "Достигнут лимит устройств"
    error is ApiError && error.code == "client_upgrade_required" -> "Обновите RockMobile"
    error is ApiError && error.code == "pairing_unavailable" -> "RockServer временно недоступен"
    error is ApiError && error.statusCode == 409 -> "Достигнут лимит устройств аккаунта. Отключите старое устройство и попробуйте снова."
    error is ApiError && error.statusCode == 410 -> "Запрос подключения больше недоступен. Создайте новый."
    error is ApiError && error.statusCode >= 500 -> "RockServer временно недоступен"
    error is ApiError -> "Не удалось подключить телефон. Проверьте ссылку и попробуйте снова."
    error is IOException -> "RockServer временно недоступен"
    else -> "Не удалось подключить телефон. Проверьте ссылку и попробуйте снова."
}

internal fun pairingErrorAction(error: Throwable, deadlineReached: Boolean = false): AccountErrorAction = when {
    deadlineReached || error is ApiError && (error.code == "pairing_expired" || error.statusCode == 410) -> AccountErrorAction.NewLink
    error is ApiError && error.code == "pairing_rejected" -> AccountErrorAction.Restart
    error is ApiError && error.code == "device_limit_reached" || error is ApiError && error.statusCode == 409 -> AccountErrorAction.OpenDevices
    error is ApiError && error.code == "client_upgrade_required" -> AccountErrorAction.UpdateApp
    else -> AccountErrorAction.Retry
}

class AccountViewModel(
    private val gateway: AccountGateway,
    private val store: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pairingTimeoutMs: Long = DEFAULT_PAIRING_TIMEOUT_MS,
    private val pairingPollMs: Long = DEFAULT_PAIRING_POLL_MS,
) : ViewModel() {
    private data class PendingPairing(val request: PairingRequest, val deadlineMs: Long)

    private val _state = MutableStateFlow<AccountUiState>(AccountUiState.Disconnected)
    val state: StateFlow<AccountUiState> = _state.asStateFlow()
    private var pairingJob: Job? = null
    private var pendingPairing: PendingPairing? = null
    private val deviceSessionMutex = Mutex()
    private val accountSessionMutex = Mutex()

    init {
        viewModelScope.launch { bootstrapAccountState() }
    }

    private suspend fun bootstrapAccountState() {
        refreshAccountInternal()
        if (!accountSessionActive(_state.value)) {
            restorePendingPairing()
        }
    }

    fun connect(deviceName: String) {
        if (_state.value is AccountUiState.Starting || pairingJob?.isActive == true) return
        val normalizedName = deviceName.trim()
        validateDeviceDisplayName(normalizedName)?.let {
            _state.value = AccountUiState.Error(it)
            return
        }
        pairingJob?.cancel()
        pendingPairing = null
        _state.value = AccountUiState.Starting
        pairingJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store.clearPendingPairing() }
                val request = withContext(ioDispatcher) { gateway.createPairing(normalizedName) }
                val deadline = min(nowMs() + pairingTimeoutMs, request.expiresAtMs() ?: Long.MAX_VALUE)
                pendingPairing = PendingPairing(request, deadline)
                withContext(ioDispatcher) { store.savePendingPairing(request.persistSnapshot(deadline)) }
                _state.value = AccountUiState.Pairing(request)
                awaitPairing(pendingPairing!!)
            } catch (_: CancellationException) {
                // A local cancel leaves anonymous radio and any saved account untouched.
            } catch (error: Exception) {
                pendingPairing = null
                withContext(ioDispatcher) { store.clearPendingPairing() }
                _state.value = AccountUiState.Error(pairingErrorMessage(error), pairingErrorAction(error))
            }
        }
    }

    /** Re-attaches polling after the browser/activity returns to the foreground. */
    fun resumePairing(fromBrowser: Boolean = false) {
        viewModelScope.launch {
            if (pendingPairing == null) restorePendingPairing()
            val pending = pendingPairing ?: run {
                if (fromBrowser) PairingLog.returnWithoutPending()
                return@launch
            }
            if (nowMs() >= pending.deadlineMs) {
                pendingPairing = null
                pairingJob?.cancel()
                clearPendingPairingStorage()
                _state.value = AccountUiState.Error("Ссылка истекла", AccountErrorAction.NewLink)
                return@launch
            }
            when (val current = _state.value) {
                is AccountUiState.Pairing -> if (fromBrowser) {
                    _state.value = current.copy(returningFromBrowser = true)
                }
                else -> if (!accountSessionActive(current)) {
                    _state.value = AccountUiState.Pairing(
                        pending.request,
                        returningFromBrowser = fromBrowser,
                    )
                }
            }
            if (pairingJob?.isActive != true) {
                pairingJob = launch {
                    try { awaitPairing(pending) } catch (_: CancellationException) { }
                }
            }
        }
    }

    fun cancelPairing() {
        pendingPairing = null
        pairingJob?.cancel()
        pairingJob = null
        viewModelScope.launch { withContext(ioDispatcher) { store.clearPendingPairing() } }
        _state.value = AccountUiState.Disconnected
    }

    /** Shows a saved native session in the UI when pairing finished while the dialog was closed. */
    fun ensureSessionVisible() {
        if (accountSessionActive(_state.value)) return
        viewModelScope.launch {
            accountSessionMutex.withLock {
                if (accountSessionActive(_state.value)) return@launch
                probeStoredSession()
            }
        }
    }

    fun acknowledgeFirstTimeConnection() {
        val firstTime = _state.value as? AccountUiState.ConnectedFirstTime ?: return
        _state.value = AccountUiState.Connected(
            firstTime.profile,
            firstTime.devices.sortedByDescending { it.deviceId == firstTime.profile.deviceId },
            devicesAvailable = firstTime.devicesAvailable,
        )
    }

    fun refreshAccount() = viewModelScope.launch { refreshAccountInternal() }

    private suspend fun refreshAccountInternal() {
        accountSessionMutex.withLock {
            probeStoredSession()
        }
    }

    private suspend fun probeStoredSession() {
        val cachedProfile = withContext(ioDispatcher) { store.loadProfile() }
        val hasCredentials = withContext(ioDispatcher) { store.load() } != null
        SessionLog.probeStarted(hasCredentials)
        if (!hasCredentials) return
        try {
            loadAccount()
            val connected = _state.value as? AccountUiState.Connected
            if (connected != null) SessionLog.probeConnected(connected.profile.deviceId)
        } catch (error: Exception) {
            when {
                error is ApiError && error.code == "device_credential_invalid" -> {
                    SessionLog.refreshFailed(error)
                    SessionLog.credentialsCleared("device credential revoked")
                    pairingJob?.cancel()
                    pendingPairing = null
                    _state.value = AccountUiState.Disconnected
                }
                error is IllegalStateException && error.message == "Failed to persist native session credentials" -> {
                    SessionLog.persistFailed(error)
                    if (cachedProfile != null) {
                        _state.value = AccountUiState.Connected(
                            cachedProfile,
                            emptyList(),
                            "Сессия обновлена на сервере, но не сохранилась на устройстве. Подключите телефон снова.",
                            devicesAvailable = false,
                        )
                    }
                }
                cachedProfile != null -> {
                    SessionLog.probeOffline("account probe degraded: ${error.message.orEmpty()}")
                    _state.value = AccountUiState.Connected(
                        cachedProfile,
                        emptyList(),
                        "Не удалось обновить аккаунт. Радио продолжает работать без сервера.",
                        devicesAvailable = false,
                    )
                }
            }
        }
    }

    fun revoke(deviceId: String) = viewModelScope.launch {
        try {
            authorized { gateway.revokeDevice(it, deviceId) }
            if ((state.value as? AccountUiState.Connected)?.profile?.deviceId == deviceId) {
                withContext(ioDispatcher) { store.clear() }
                _state.value = AccountUiState.Disconnected
            } else {
                loadAccount()
            }
        } catch (_: Exception) {
            val connected = state.value as? AccountUiState.Connected
            if (connected != null) {
                _state.value = connected.copy(message = "Не удалось отключить устройство. Проверьте соединение с RockServer.")
            }
        }
    }

    fun logout() = viewModelScope.launch {
        val credentials = withContext(ioDispatcher) { store.load() }
        try {
            if (credentials != null) authorized { gateway.revokeDevice(it, credentials.deviceId) }
            withContext(ioDispatcher) { store.clear() }
            _state.value = AccountUiState.Disconnected
        } catch (_: Exception) {
            val connected = state.value as? AccountUiState.Connected
            if (connected != null) {
                _state.value = connected.copy(message = "Не удалось отключить устройство. Проверьте соединение с RockServer.")
            }
        }
    }

    private suspend fun loadAccount(profile: AccountProfile? = null) {
        val actualProfile = profile ?: authorized { gateway.profile(it) }
        withContext(ioDispatcher) { store.saveProfile(actualProfile) }
        var devicesAvailable = true
        val devices = try {
            authorized { gateway.devices(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiError) {
            if (error.statusCode == 401) throw error
            devicesAvailable = false
            emptyList()
        } catch (_: Exception) {
            devicesAvailable = false
            emptyList()
        }
        _state.value = AccountUiState.Connected(
            actualProfile,
            devices,
            (state.value as? AccountUiState.Connected)?.message,
            devicesAvailable,
        )
    }

    private suspend fun <T> authorized(block: (String) -> T): T {
        val credentials = withContext(ioDispatcher) { store.load() } ?: throw IllegalStateException("No session")
        return try {
            withContext(ioDispatcher) { block(credentials.accessToken) }
        } catch (error: ApiError) {
            if (error.statusCode != 401) throw error
            val fresh = renewDeviceSession(credentials.deviceId)
            withContext(ioDispatcher) { block(fresh.accessToken) }
        }
    }

    /** Replaces only the short-lived access token; the durable device secret never rotates. */
    private suspend fun renewDeviceSession(expectedDeviceId: String): NativeCredentials =
        deviceSessionMutex.withLock {
            val current = withContext(ioDispatcher) { store.load() }
                ?: throw IllegalStateException("No session")
            if (current.deviceId != expectedDeviceId) {
                return current
            }
            try {
                val freshAccessToken = withContext(ioDispatcher) {
                    gateway.createDeviceSession(current.deviceId, current.deviceSecret)
                }
                val fresh = NativeCredentials(current.deviceId, current.deviceSecret, freshAccessToken)
                persistCredentials(fresh)
                fresh
            } catch (error: ApiError) {
                if (error.code == "device_credential_invalid") {
                    val stillCurrent = withContext(ioDispatcher) { store.load() }
                    if (stillCurrent?.deviceId == expectedDeviceId) {
                        SessionLog.credentialsCleared("device credential revoked")
                        withContext(ioDispatcher) { store.clear() }
                    }
                }
                throw error
            }
        }

    private suspend fun persistCredentials(credentials: NativeCredentials) {
        withContext(ioDispatcher) { store.save(credentials) }
    }

    private suspend fun awaitPairing(pending: PendingPairing) {
        while (pendingPairing == pending && nowMs() < pending.deadlineMs) {
            try {
                val result = withContext(ioDispatcher) { gateway.completePairing(pending.request) }
                if (pendingPairing != pending) return
                withContext(ioDispatcher) {
                    persistCredentials(result.second)
                    store.saveProfile(result.first)
                    store.clearPendingPairing()
                }
                pendingPairing = null
                loadAccount(result.first)
                val connected = state.value as? AccountUiState.Connected
                _state.value = AccountUiState.ConnectedFirstTime(
                    result.first,
                    connected?.devices.orEmpty(),
                    connected?.devicesAvailable ?: true,
                )
                return
            } catch (error: Exception) {
                val now = nowMs()
                if (!shouldRetryPairingPoll(error, now, pending.deadlineMs)) {
                    pendingPairing = null
                    clearPendingPairingStorage()
                    PairingLog.pollFailed(error)
                    _state.value = AccountUiState.Error(
                        pairingErrorMessage(error, now >= pending.deadlineMs),
                        pairingErrorAction(error, now >= pending.deadlineMs),
                    )
                    return
                }
                PairingLog.pollRetry(error)
            }
            delay(min(pairingPollMs, (pending.deadlineMs - nowMs()).coerceAtLeast(1)))
        }
        pendingPairing = null
        clearPendingPairingStorage()
        _state.value = AccountUiState.Error("Ссылка истекла", AccountErrorAction.NewLink)
    }

    private suspend fun restorePendingPairing() {
        if (withContext(ioDispatcher) { store.load() } != null) {
            clearPendingPairingStorage()
            return
        }
        val snapshot = withContext(ioDispatcher) { store.loadPendingPairing() } ?: return
        if (nowMs() >= snapshot.deadlineMs) {
            clearPendingPairingStorage()
            return
        }
        val request = snapshot.toPairingRequest()
        val pending = PendingPairing(request, snapshot.deadlineMs)
        pendingPairing = pending
        if (!accountSessionActive(_state.value)) {
            _state.value = AccountUiState.Pairing(request)
        }
        PairingLog.restored(request.requestId)
        if (pairingJob?.isActive != true) {
            pairingJob = viewModelScope.launch {
                try { awaitPairing(pending) } catch (_: CancellationException) { }
            }
        }
    }

    private suspend fun clearPendingPairingStorage() = withContext(ioDispatcher) { store.clearPendingPairing() }

    fun openDevices() {
        val firstTime = _state.value as? AccountUiState.ConnectedFirstTime ?: return
        _state.value = AccountUiState.Connected(
            firstTime.profile,
            firstTime.devices.sortedByDescending { it.deviceId == firstTime.profile.deviceId },
            devicesAvailable = firstTime.devicesAvailable,
        )
    }

    override fun onCleared() { pairingJob?.cancel() }
}

internal fun accountSessionActive(state: AccountUiState): Boolean =
    state is AccountUiState.Connected || state is AccountUiState.ConnectedFirstTime
