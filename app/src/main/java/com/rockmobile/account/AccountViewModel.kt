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
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

private const val DEFAULT_PAIRING_TIMEOUT_MS = 10 * 60 * 1000L
private const val DEFAULT_PAIRING_POLL_MS = 2_000L

internal fun shouldContinuePairing(error: Throwable, nowMs: Long, deadlineMs: Long): Boolean =
    error is ApiError && error.code == "pairing_pending" && nowMs < deadlineMs

internal fun pairingErrorMessage(error: Throwable, deadlineReached: Boolean = false): String = when {
    deadlineReached -> "Срок действия подключения истёк. Начните подключение заново."
    error is ApiError && error.code == "pairing_rejected" -> "Запрос подключения отклонён. Создайте новый."
    error is ApiError && error.code == "pairing_expired" -> "Срок действия подключения истёк. Начните подключение заново."
    error is ApiError && error.code == "device_limit_reached" -> "Достигнут лимит устройств аккаунта. Отключите старое устройство и попробуйте снова."
    error is ApiError && error.code == "client_upgrade_required" -> "Обновите RockMobile, чтобы продолжить подключение."
    error is ApiError && error.code == "pairing_unavailable" -> "RockServer сейчас недоступен. Радио продолжает работать без сервера."
    error is ApiError && error.statusCode == 409 -> "Достигнут лимит устройств аккаунта. Отключите старое устройство и попробуйте снова."
    error is ApiError && error.statusCode == 410 -> "Запрос подключения больше недоступен. Создайте новый."
    error is ApiError && error.statusCode >= 500 -> "RockServer сейчас недоступен. Радио продолжает работать без сервера."
    error is ApiError -> "Не удалось подключить телефон. Проверьте ссылку и попробуйте снова."
    error is IOException -> "RockServer сейчас недоступен. Радио продолжает работать без сервера."
    else -> "Не удалось подключить телефон. Проверьте ссылку и попробуйте снова."
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

    init {
        refreshAccount()
    }

    fun connect(deviceName: String) {
        val normalizedName = deviceName.trim()
        validateDeviceDisplayName(normalizedName)?.let {
            _state.value = AccountUiState.Error(it)
            return
        }
        pairingJob?.cancel()
        pendingPairing = null
        pairingJob = viewModelScope.launch {
            try {
                val request = withContext(ioDispatcher) { gateway.createPairing(normalizedName) }
                val deadline = min(nowMs() + pairingTimeoutMs, request.expiresAtMs() ?: Long.MAX_VALUE)
                pendingPairing = PendingPairing(request, deadline)
                _state.value = AccountUiState.Pairing(request)
                awaitPairing(pendingPairing!!)
            } catch (_: CancellationException) {
                // A local cancel leaves anonymous radio and any saved account untouched.
            } catch (error: Exception) {
                pendingPairing = null
                _state.value = AccountUiState.Error(pairingErrorMessage(error))
            }
        }
    }

    /** Re-attaches polling after the browser/activity returns to the foreground. */
    fun resumePairing() {
        val pending = pendingPairing ?: return
        if (nowMs() >= pending.deadlineMs) {
            pendingPairing = null
            pairingJob?.cancel()
            _state.value = AccountUiState.Error("Срок действия подключения истёк. Начните подключение заново.")
        } else if (pairingJob?.isActive != true) {
            pairingJob = viewModelScope.launch {
                try { awaitPairing(pending) } catch (_: CancellationException) { }
            }
        }
    }

    fun cancelPairing() {
        pendingPairing = null
        pairingJob?.cancel()
        pairingJob = null
        _state.value = AccountUiState.Disconnected
    }

    fun refreshAccount() = viewModelScope.launch {
        val localProfile = withContext(ioDispatcher) { store.loadProfile() }
        try {
            val credentials = withContext(ioDispatcher) { store.load() } ?: return@launch
            val fresh = withContext(ioDispatcher) { gateway.refresh(credentials.refreshToken) }
            withContext(ioDispatcher) { store.save(fresh) }
            loadAccount()
        } catch (error: Exception) {
            if (error is ApiError && error.statusCode == 401) {
                withContext(ioDispatcher) { store.clear() }
                _state.value = AccountUiState.Disconnected
            } else if (localProfile != null) {
                _state.value = AccountUiState.Connected(
                    localProfile,
                    emptyList(),
                    "Не удалось обновить аккаунт. Радио продолжает работать без сервера.",
                    devicesAvailable = false,
                )
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
        try { if (credentials != null) withContext(ioDispatcher) { gateway.logout(credentials.accessToken) } } catch (_: Exception) { }
        withContext(ioDispatcher) { store.clear() }
        _state.value = AccountUiState.Disconnected
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
            val fresh = withContext(ioDispatcher) { gateway.refresh(credentials.refreshToken).also(store::save) }
            withContext(ioDispatcher) { block(fresh.accessToken) }
        }
    }

    private suspend fun awaitPairing(pending: PendingPairing) {
        while (pendingPairing == pending && nowMs() < pending.deadlineMs) {
            try {
                val result = withContext(ioDispatcher) { gateway.completePairing(pending.request) }
                if (pendingPairing != pending) return
                withContext(ioDispatcher) {
                    store.save(result.second)
                    store.saveProfile(result.first)
                }
                pendingPairing = null
                loadAccount(result.first)
                val connected = state.value as? AccountUiState.Connected
                _state.value = AccountUiState.Connected(
                    result.first,
                    connected?.devices.orEmpty(),
                    "Этот телефон подключён к ${result.first.accountDisplayName}",
                    connected?.devicesAvailable ?: true,
                )
                return
            } catch (error: Exception) {
                val now = nowMs()
                if (!shouldContinuePairing(error, now, pending.deadlineMs)) {
                    pendingPairing = null
                    _state.value = AccountUiState.Error(pairingErrorMessage(error, now >= pending.deadlineMs))
                    return
                }
            }
            delay(min(pairingPollMs, (pending.deadlineMs - nowMs()).coerceAtLeast(1)))
        }
        pendingPairing = null
        _state.value = AccountUiState.Error("Срок действия подключения истёк. Начните подключение заново.")
    }

    override fun onCleared() { pairingJob?.cancel() }
}
