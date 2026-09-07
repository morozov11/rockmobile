package com.rockmobile.devicecontrol

import com.rockmobile.BuildConfig
import com.rockmobile.account.SessionLog
import com.rockmobile.data.api.RockserverApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface DirectorySocketFactory {
    fun connect(baseUrl: String, accessToken: String, listener: DirectorySocketListener): DirectorySocketConnection
}
internal interface DirectorySocketConnection : Closeable { fun send(command: DeviceCommandPayloadDto): Boolean }
internal interface DirectorySocketListener {
    fun onMessage(message: DirectoryWireMessage)
    fun onClosed(resyncRequired: Boolean)
}

/** Bounded authenticated controller connection; all commands use the same native-session lifecycle. */
internal class OkHttpDirectorySocketFactory(
    // OkHttp applies its default 10 s read timeout to idle WebSocket reads too, but the
    // control protocol is quiet between 20 s application heartbeats, so a finite read
    // timeout kills a healthy controller socket before its first heartbeat. Protocol
    // pings every 15 s keep the stream alive and still detect a dead server within 30 s.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
) : DirectorySocketFactory {
    override fun connect(baseUrl: String, accessToken: String, listener: DirectorySocketListener): DirectorySocketConnection {
        val request = Request.Builder().url(controlSocketUrl(baseUrl)).header("Authorization", "Bearer $accessToken").build()
        var socket: WebSocket? = null
        var closed = false
        var heartbeat: Thread? = null
        val notified = AtomicBoolean(false)
        fun notifyClosed(resyncRequired: Boolean) {
            if (!closed && notified.compareAndSet(false, true)) listener.onClosed(resyncRequired)
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(DirectoryJson.codec.encodeToString(hello()))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray().size > MAX_FRAME_BYTES) { webSocket.close(1009, "frame_too_large"); return }
                var frameType = "unknown"
                runCatching {
                    frameType = DirectoryJson.codec.decodeFromString<WireHeaderDto>(text).type
                    decodeDirectoryMessage(text)
                }.onSuccess { message ->
                    listener.onMessage(message)
                    when (message) {
                        DirectoryWireMessage.Welcome -> webSocket.send(DirectoryJson.codec.encodeToString(register()))
                        DirectoryWireMessage.Registered -> if (heartbeat == null) {
                            heartbeat = heartbeatThread(webSocket)
                        }
                        else -> Unit
                    }
                }.onFailure { SessionLog.probeOffline("directory frame invalid: $frameType"); webSocket.close(1007, "invalid_message") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.close(1003, "binary_not_supported") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { SessionLog.probeOffline("directory socket closed: $code $reason"); notifyClosed(reason.contains("directory_resync_required")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { SessionLog.probeOffline("directory socket failed: ${response?.code ?: t.javaClass.simpleName}"); notifyClosed(false) }
        })
        return object : DirectorySocketConnection {
            override fun send(command: DeviceCommandPayloadDto): Boolean = socket?.send(commandFrame(command)) == true
            override fun close() { closed = true; heartbeat?.interrupt(); socket?.close(1000, "selector_closed") }
        }
    }

    private fun heartbeatThread(socket: WebSocket) = Thread {
        var sequence = 0L
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(20_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            socket.send(DirectoryJson.codec.encodeToString(heartbeat(sequence++)))
        }
    }.apply { isDaemon = true; start() }

    private companion object { const val MAX_FRAME_BYTES = 65_536 }
}

@Serializable private data class OutgoingEnvelope<T>(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("message_id") val messageId: String = UUID.randomUUID().toString(),
    val type: String,
    @SerialName("sent_at") val sentAt: String = Instant.now().toString(),
    val payload: T,
)
@Serializable private data class HelloPayload(@SerialName("supported_protocol_versions") val versions: List<Int> = listOf(1))
@Serializable private data class RegisterPayload(
    @SerialName("device_type") val deviceType: String = "rockmobile_android",
    @SerialName("app_version") val appVersion: String = BuildConfig.VERSION_NAME,
    val manifest: ControllerManifest = ControllerManifest(),
)
@Serializable private data class ControllerManifest(
    @SerialName("manifest_revision") val revision: Long = 1,
    val roles: List<String> = listOf("controller"),
    val capabilities: EmptyCapabilities = EmptyCapabilities(),
    val entities: List<String> = emptyList(),
    val surfaces: List<String> = emptyList(),
)
@Serializable private data class EmptyCapabilities(val revision: Long = 1, val items: List<String> = emptyList())
@Serializable private data class HeartbeatPayload(val sequence: Long)
private fun hello() = OutgoingEnvelope(type = "protocol.hello", payload = HelloPayload())
private fun register() = OutgoingEnvelope(type = "device.register", payload = RegisterPayload())
private fun heartbeat(sequence: Long) = OutgoingEnvelope(type = "device.heartbeat", payload = HeartbeatPayload(sequence))

/** Kept typed for wire assertions; it contains no controller identity or untyped payload. */
internal fun commandFrame(command: DeviceCommandPayloadDto): String =
    DirectoryJson.codec.encodeToString(OutgoingEnvelope(type = "device.command", payload = command))

internal fun controlSocketUrl(baseUrl: String): String =
    baseUrl.trim().trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "${RockserverApi.API_V1_PREFIX}/devices/connect"
