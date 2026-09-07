package com.rockmobile.devicecontrol

import com.rockmobile.account.SessionLog
import com.rockmobile.data.api.ApiError
import java.time.Instant
import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<TargetDirectoryState>(TargetDirectoryState.Inactive)
    val state: StateFlow<TargetDirectoryState> = _state.asStateFlow()
    private var session: ControllerSession? = null
    private var scope: CoroutineScope? = null
    private var socket: DirectorySocketConnection? = null
    private var reconnect: Job? = null
    private var revision = 0L
    private var targets = emptyMap<String, ControllerTarget>()
    private var scopes = emptySet<String>()
    private var reconnectAttempt = 0
    private val _commands = MutableStateFlow<Map<String, CommandLifecycle>>(emptyMap())
    val commands: StateFlow<Map<String, CommandLifecycle>> = _commands.asStateFlow()
    private val _receivers = MutableStateFlow<List<EphemeralReceiver>>(emptyList())
    val receivers: StateFlow<List<EphemeralReceiver>> = _receivers.asStateFlow()

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
        session = null; scope = null; revision = 0L; targets = emptyMap(); scopes = emptySet(); reconnectAttempt = 0
        _commands.value = emptyMap(); _receivers.value = emptyList()
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

    /** Refuses implicit, stale, unauthorized and duplicate dispatch before any frame leaves the phone. */
    fun dispatch(body: RemoteCommand, now: Instant = Instant.now()): String? {
        val active = session ?: return null
        val selected = currentSelected() ?: run { publish("Выберите доступное устройство явно."); return null }
        if (!selected.usable || "media.control" !in scopes) { publish("Управление этим устройством сейчас недоступно."); return null }
        if (!commandAllowed(selected, body, now)) { publish("Действие не поддерживается выбранным устройством."); return null }
        if (_commands.value.values.any { it.targetId == selected.id && it.actionKey == body.key && it.inFlight }) return null
        val payload = newCommand(selected.id, body, now)
        val lifecycle = CommandLifecycle(payload.commandId, selected.id, body.key, CommandPhase.Pending)
        _commands.value += payload.commandId to lifecycle
        if (socket?.send(payload) != true) {
            _commands.value += payload.commandId to lifecycle.copy(phase = CommandPhase.Failed, detail = "Соединение с сервером потеряно.")
            return null
        }
        scope?.launch {
            delay(10_000)
            _commands.value[payload.commandId]?.takeIf { it.inFlight }?.let {
                _commands.value += payload.commandId to it.copy(phase = CommandPhase.Expired, detail = "Время ожидания команды истекло.")
            }
        }
        return payload.commandId
    }

    private suspend fun reload(connectAfter: Boolean) {
        val active = session ?: return
        try {
            applySnapshot(withContext(ioDispatcher) { api.load(active.accessToken) })
            if (connectAfter) openSocket(active)
        } catch (error: Exception) {
            val scopeMissing = error is ApiError && error.statusCode == 403
            if (!scopeMissing) SessionLog.refreshFailed(error)
            if (scopeMissing && connectAfter) {
                openSocket(active)
                return
            }
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
        is DirectoryWireMessage.Removed -> {
            cancelTargetCommands(message.deviceId, "Выбранное устройство удалено или доступ отозван.")
            applyChange(message.revision) { targets - message.deviceId }
        }
        is DirectoryWireMessage.CommandReceived -> updateCommand(message.commandId) { it.copy(phase = CommandPhase.Received, detail = if (message.duplicate) "Сервер повторно подтвердил ту же команду." else null) }
        is DirectoryWireMessage.CommandAccepted -> updateCommand(message.commandId) { it.copy(phase = CommandPhase.Accepted) }
        is DirectoryWireMessage.CommandResult -> handleResult(message)
        is DirectoryWireMessage.CommandError -> message.commandId?.let { id -> updateCommand(id) { it.copy(phase = CommandPhase.Failed, detail = message.error.message) } }
        DirectoryWireMessage.Welcome, DirectoryWireMessage.Registered -> Unit
        DirectoryWireMessage.ResyncRequired -> reload(connectAfter = true)
        DirectoryWireMessage.IgnoredUnknown -> Unit
    }

    private suspend fun applySnapshot(directory: DirectoryDto, resetReconnect: Boolean = false) {
        require(directory.protocolVersion == 1 && directory.revision >= 1 && directory.devices.size <= 50) { "Invalid snapshot" }
        revision = directory.revision
        scopes = directory.grantedScopes.toSet()
        targets = directory.devices.map { it.toTarget() }.associateBy(ControllerTarget::id)
        _commands.value.values.filter { it.phase == CommandPhase.AwaitingState }.forEach { command ->
            if (targets[command.targetId]?.usable == true) _commands.value += command.commandId to command.copy(phase = CommandPhase.Succeeded)
        }
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
        _state.value = TargetDirectoryState.Available(targets.values.sortedBy { it.name }, selected, scopes, invalidation)
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

    private fun currentSelected(): ControllerTarget? = session?.let { active ->
        selections.load(active.userId, active.deviceId)?.let(targets::get)
    }

    private fun updateCommand(commandId: String, update: (CommandLifecycle) -> CommandLifecycle) {
        _commands.value[commandId]?.takeIf { !it.phase.terminal }?.let { _commands.value += commandId to update(it) }
    }

    private suspend fun handleResult(message: DirectoryWireMessage.CommandResult) {
        val command = _commands.value[message.commandId] ?: return
        if (command.phase.terminal) return
        if (message.status == CommandResultStatus.Failed) {
            _commands.value += command.commandId to command.copy(phase = CommandPhase.Failed, detail = message.error?.message ?: "Команда отклонена сервером.")
            reload(connectAfter = true)
            return
        }
        message.output?.receivers?.mapNotNull(::receiver)?.let { discovered -> _receivers.value = discovered.filter { it.validAt(Instant.now()) } }
        _commands.value += command.commandId to command.copy(phase = CommandPhase.AwaitingState, detail = "Команда завершена; обновляем фактическое состояние.")
        reload(connectAfter = true)
    }

    private fun receiver(dto: ChromecastReceiverDto): EphemeralReceiver? = runCatching {
        EphemeralReceiver(dto.receiverId, dto.displayName, Instant.parse(dto.expiresAt)).takeIf { it.receiverId.isNotBlank() && it.displayName.isNotBlank() }
    }.getOrNull()

    private fun cancelTargetCommands(targetId: String, detail: String) {
        _commands.value.filterValues { it.targetId == targetId && it.inFlight }.forEach { (id, command) ->
            _commands.value += id to command.copy(phase = CommandPhase.Cancelled, detail = detail)
        }
    }

    private fun commandAllowed(target: ControllerTarget, body: RemoteCommand, now: Instant): Boolean = when (body) {
        RemoteCommand.Play -> target.capability<ControlCapability.Playback>()?.actions?.contains(PlaybackAction.Play) == true
        RemoteCommand.Pause -> target.capability<ControlCapability.Playback>()?.actions?.contains(PlaybackAction.Pause) == true
        RemoteCommand.Stop -> target.capability<ControlCapability.Playback>()?.actions?.contains(PlaybackAction.Stop) == true
        RemoteCommand.Next -> target.capability<ControlCapability.Playback>()?.actions?.contains(PlaybackAction.Next) == true
        RemoteCommand.Previous -> target.capability<ControlCapability.Playback>()?.actions?.contains(PlaybackAction.Previous) == true
        is RemoteCommand.PlayStation -> target.capability<ControlCapability.Station>()?.sources?.contains(StationSource.RockserverCatalog) == true && body.stationId.length in 1..128
        is RemoteCommand.PlayStream -> target.capability<ControlCapability.Station>()?.sources?.contains(StationSource.DirectStream) == true && validStreamUri(body.streamUri) && body.source == "direct_stream"
        is RemoteCommand.SetVolume -> target.capability<ControlCapability.Volume>()?.let { body.level in it.minimum..it.maximum && (body.level - it.minimum) % it.step == 0 } == true
        is RemoteCommand.ChangeVolume -> target.capability<ControlCapability.Volume>()?.let { body.delta != 0 && body.delta in -it.maximum..it.maximum && body.delta % it.step == 0 } == true
        is RemoteCommand.SetMute -> target.capability<ControlCapability.Volume>()?.mute == true
        RemoteCommand.Discover -> target.capability<ControlCapability.Chromecast>()?.actions?.contains(ChromecastAction.Discover) == true
        is RemoteCommand.Connect -> target.capability<ControlCapability.Chromecast>()?.actions?.contains(ChromecastAction.Connect) == true && _receivers.value.any { it.receiverId == body.receiverId && it.validAt(now) }
        RemoteCommand.Disconnect -> target.capability<ControlCapability.Chromecast>()?.actions?.contains(ChromecastAction.Disconnect) == true
        RemoteCommand.StartRelay -> target.capability<ControlCapability.Relay>()?.actions?.contains(RelayAction.Start) == true
        RemoteCommand.StopRelay -> target.capability<ControlCapability.Relay>()?.actions?.contains(RelayAction.Stop) == true
        is RemoteCommand.SetRelayMode -> target.capability<ControlCapability.Relay>()?.let { RelayAction.SetMode in it.actions && body.mode in it.modes } == true
    }
}

private fun validStreamUri(value: String): Boolean = runCatching {
    URI(value).let { it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null && it.fragment == null && (it.port in -1..65535) }
}.getOrDefault(false)

private val CommandPhase.terminal: Boolean get() = this == CommandPhase.Succeeded || this == CommandPhase.Failed || this == CommandPhase.Cancelled || this == CommandPhase.Expired
