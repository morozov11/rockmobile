package com.rockmobile.devicecontrol

import com.rockmobile.data.api.ApiError
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

interface TargetSelectionStore {
    fun load(userId: String, controllerDeviceId: String): String?
    fun save(userId: String, controllerDeviceId: String, targetId: String)
    fun clear(userId: String, controllerDeviceId: String)
}

/** Owner-scoped directory and explicit target selection. No command API exists in this module. */
internal class TargetDirectoryRepository(
    private val api: DeviceControlDirectoryApi,
    private val socketFactory: DirectorySocketFactory,
    private val selections: TargetSelectionStore,
    private val reconnectDelayMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<TargetDirectoryState>(TargetDirectoryState.Inactive)
    val state: StateFlow<TargetDirectoryState> = _state.asStateFlow()
    private var session: ControllerSession? = null
    private var scope: CoroutineScope? = null
    private var socket: Closeable? = null
    private var reconnect: Job? = null
    private var revision = 0L
    private var targets = emptyMap<String, ControllerTarget>()
    private var reconnectAttempt = 0

    fun start(scope: CoroutineScope, session: ControllerSession) {
        if (this.session?.userId == session.userId && this.session?.deviceId == session.deviceId && socket != null) {
            this.session = session
            return
        }
        stop()
        this.scope = scope
        this.session = session
        _state.value = TargetDirectoryState.Loading
        scope.launch { reload(connectAfter = true) }
    }

    fun stop() {
        reconnect?.cancel(); reconnect = null
        socket?.close(); socket = null
        session = null; scope = null; revision = 0L; targets = emptyMap(); reconnectAttempt = 0
        _state.value = TargetDirectoryState.Inactive
    }

    fun refresh() { reconnectAttempt = 0; scope?.launch { reload(connectAfter = true) } }

    fun select(targetId: String) {
        val active = session ?: return
        val target = targets[targetId] ?: return
        if (!target.usable) {
            publish("Выберите доступное устройство: выбранное сейчас недоступно.")
            return
        }
        selections.save(active.userId, active.deviceId, target.id)
        publish()
    }

    private suspend fun reload(connectAfter: Boolean) {
        val active = session ?: return
        try {
            applySnapshot(api.load(active.accessToken))
            if (connectAfter) openSocket(active)
        } catch (error: Exception) {
            val scopeMissing = error is ApiError && error.statusCode == 403
            _state.value = TargetDirectoryState.Unavailable(
                if (scopeMissing) "Для выбора устройства нет разрешения directory." else "Список устройств управления временно недоступен.",
                scopeMissing,
            )
            if (connectAfter && !scopeMissing) scheduleReconnect()
        }
    }

    private fun openSocket(active: ControllerSession) {
        socket?.close()
        socket = socketFactory.connect(activeSessionBaseUrl(), active.accessToken, object : DirectorySocketListener {
            override fun onMessage(message: DirectoryWireMessage) {
                scope?.launch { handle(message) }
            }
            override fun onClosed(resyncRequired: Boolean) {
                scope?.launch { if (resyncRequired) reload(connectAfter = true) else scheduleReconnect() }
            }
        })
    }

    /* API owns URL selection; keeping it here prevents a second endpoint/credential configuration. */
    private fun activeSessionBaseUrl(): String = api.baseUrl()

    private suspend fun handle(message: DirectoryWireMessage) = when (message) {
        is DirectoryWireMessage.Snapshot -> applySnapshot(message.directory, resetReconnect = true)
        is DirectoryWireMessage.Upsert -> applyChange(message.revision) { targets + (message.device.toTarget().id to message.device.toTarget()) }
        is DirectoryWireMessage.Removed -> applyChange(message.revision) { targets - message.deviceId }
        DirectoryWireMessage.ResyncRequired -> reload(connectAfter = true)
        DirectoryWireMessage.IgnoredUnknown -> Unit
    }

    private suspend fun applySnapshot(directory: DirectoryDto, resetReconnect: Boolean = false) {
        require(directory.protocolVersion == 1 && directory.revision >= 1 && directory.devices.size <= 50) { "Invalid snapshot" }
        revision = directory.revision
        targets = directory.devices.map { it.toTarget() }.associateBy(ControllerTarget::id)
        if (resetReconnect) reconnectAttempt = 0
        publish()
    }

    private suspend fun applyChange(nextRevision: Long, change: () -> Map<String, ControllerTarget>) {
        if (nextRevision <= revision) return
        if (nextRevision != revision + 1) { reload(connectAfter = true); return }
        targets = change(); revision = nextRevision; publish()
    }

    private fun publish(notice: String? = null) {
        val active = session ?: return
        val saved = selections.load(active.userId, active.deviceId)
        val selected = saved?.takeIf { targets[it]?.usable == true }
        if (saved != null && selected == null) selections.clear(active.userId, active.deviceId)
        val invalidation = if (saved != null && selected == null) "Выбранное устройство больше недоступно. Выберите другое явно." else notice
        _state.value = TargetDirectoryState.Available(targets.values.sortedBy { it.name }, selected, invalidation)
    }

    private fun scheduleReconnect() {
        if (session == null || reconnect?.isActive == true) return
        reconnect = scope?.launch {
            val multiplier = 1L shl reconnectAttempt.coerceAtMost(5)
            reconnectAttempt++
            delay(min(reconnectDelayMs * multiplier, 30_000))
            reload(connectAfter = true)
        }
    }
}
