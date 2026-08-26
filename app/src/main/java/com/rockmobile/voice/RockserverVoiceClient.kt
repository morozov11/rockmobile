package com.rockmobile.voice

import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated network boundary for Rockserver's voice WebSocket protocol. */
interface VoiceCommandClient {
    suspend fun resolve(baseUrl: String, bearerToken: String, audio: RecordedVoice, onTranscript: (String) -> Unit): VoiceResolution
}

sealed interface VoiceResolution {
    data class StationMatch(val transcript: String, val selected: Station, val candidates: List<Station>) : VoiceResolution
    data class NoMatch(val transcript: String) : VoiceResolution
}

class RockserverVoiceClient(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()) : VoiceCommandClient {
    override suspend fun resolve(baseUrl: String, bearerToken: String, audio: RecordedVoice, onTranscript: (String) -> Unit): VoiceResolution {
        val deferred = CompletableDeferred<VoiceResolution>()
        val ready = AtomicBoolean(false)
        val url = voiceStreamUrl(baseUrl)
        val request = Request.Builder().url(url).apply { if (bearerToken.isNotBlank()) header("Authorization", "Bearer $bearerToken") }.build()
        var socket: WebSocket? = null
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Public contract: first client frame must be start; audio only after ready.
                webSocket.send(voiceStartMessage(audio.sampleRateHz))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "ready" -> {
                            if (ready.compareAndSet(false, true)) sendBufferedAudio(webSocket, audio)
                            null
                        }
                        else -> parseRockserverVoiceEvent(text, onTranscript)
                    }
                }.onSuccess { resolution ->
                    if (resolution != null && deferred.complete(resolution)) webSocket.close(1000, null)
                }.onFailure { deferred.completeExceptionally(it); webSocket.close(1002, null) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
            }
        })
        return try { withTimeout(30_000) { deferred.await() } } finally { socket?.cancel() }
    }

    private fun sendBufferedAudio(webSocket: WebSocket, audio: RecordedVoice) {
        var offset = 0
        while (offset < audio.pcmS16Le.size) {
            val end = minOf(offset + MAX_CHUNK_BYTES, audio.pcmS16Le.size)
            webSocket.send(audio.pcmS16Le.copyOfRange(offset, end).toByteString())
            offset = end
        }
        webSocket.send(JSONObject().put("type", "commit").toString())
    }

    private companion object {
        /** Matches RockServer `MAX_STREAM_AUDIO_CHUNK_BYTES` / public OpenAPI max_frame_bytes. */
        const val MAX_CHUNK_BYTES = 32 * 1024
    }
}

class RockserverVoiceException(val code: String, message: String) : IOException(message)

/** Strictly validates one Rockserver event before it can affect playback. */
internal fun parseRockserverVoiceEvent(text: String, onTranscript: (String) -> Unit): VoiceResolution? {
    val event = JSONObject(text)
    return when (event.optString("type")) {
        "ready" -> null
        "transcript" -> { onTranscript(event.required("transcript")); null }
        "error" -> throw RockserverVoiceException(event.optString("code", "voice_error"), event.optString("message", "Voice request failed"))
        "result" -> {
            val transcript = event.required("transcript")
            onTranscript(transcript)
            val selectedJson = event.optJSONObject("selected_station") ?: return VoiceResolution.NoMatch(transcript)
            val candidates = event.optJSONArray("stations").toStations()
            val selected = selectedJson.toStation()
            VoiceResolution.StationMatch(transcript, selected, candidates.ifEmpty { listOf(selected) })
        }
        else -> throw IllegalArgumentException("Unknown Rockserver voice event")
    }
}

private fun JSONObject.toStation(): Station {
    val stream = required("stream_url")
    require(stream.startsWith("http://") || stream.startsWith("https://")) { "Invalid station stream URL" }
    return Station(
        required("id"), required("name"), stream,
        tags = optJSONArray("tags").strings(),
        country = optional("country_code"), language = optional("language"),
        codec = optional("codec"), bitrateKbps = optInt("bitrate_kbps").takeIf { it > 0 },
        homepageUrl = optional("homepage_url"), faviconUrl = optional("favicon_url"),
    )
}
private fun JSONArray?.toStations() = this?.let { array -> (0 until array.length()).map { array.getJSONObject(it).toStation() } }.orEmpty()
private fun JSONArray?.strings() = this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) } }.orEmpty()
private fun JSONObject.required(name: String) = optString(name).trim().also { require(it.isNotEmpty()) { "Voice response is missing $name" } }
private fun JSONObject.optional(name: String) = optString(name).trim().takeIf(String::isNotEmpty)

/** Maps an HTTPS RockServer base URL onto the public voice WebSocket path (TLS preserved). */
internal fun voiceStreamUrl(baseUrl: String): String =
    baseUrl.trim().trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/voice/stream"

/** Public buffered voice start frame; limit is clamped server-side to at most 10. */
internal fun voiceStartMessage(sampleRateHz: Int): String =
    JSONObject()
        .put("type", "start")
        .put("locale", "ru-RU")
        .put("sample_rate_hz", sampleRateHz)
        .put("recognizer_mode", "buffered_v1")
        .put("limit", 10)
        .toString()
