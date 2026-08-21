package com.rockmobile.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rockmobile.domain.model.Station
import com.rockmobile.settings.UnavailableVoiceStationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val station: Station? = null,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val streamTitle: String? = null,
    val streamArtist: String? = null,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
)

/** Activity-scoped proxy to the service session; the service remains the sole ExoPlayer owner. */
class PlaybackController(
    context: Context,
    private val unavailableVoiceStations: UnavailableVoiceStationStore,
) : Player.Listener {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        context, SessionToken(context, ComponentName(context, RockmobileMediaSessionService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null
    private var queue: List<Station> = emptyList()
    private var pendingPlay: PendingPlay? = null
    private var volumeBeforeVoiceCapture: Float? = null
    private var awaitingVoiceStationStart = false
    /** Desired state while the asynchronous MediaController connection is still pending. */
    private var pendingShouldPlay = true

    init {
        future.addListener({
            controller = future.get().also { it.addListener(this); pendingPlay?.let { play(it.station, it.stations, it.fromVoiceResult) }; publish() }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(station: Station, stations: List<Station> = listOf(station), fromVoiceResult: Boolean = false) {
        val playableStations = stations.ifEmpty { listOf(station) }
        // A deferred replay preserves a user toggle made before the MediaSession connected.
        val deferredReplay = controller != null && pendingPlay?.station?.id == station.id
        if (!deferredReplay) pendingShouldPlay = true
        awaitingVoiceStationStart = fromVoiceResult
        val index = playableStations.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        queue = playableStations
        _state.value = _state.value.copy(station = station, error = null, streamTitle = null, streamArtist = null,
            canSkipPrevious = index > 0, canSkipNext = index < playableStations.lastIndex)
        val mediaItems = playableStations.map(::mediaItem)
        val activeController = controller
        if (activeController == null) { pendingPlay = PendingPlay(station, playableStations, fromVoiceResult); pendingShouldPlay = true; return }
        pendingPlay = null
        activeController.setMediaItems(mediaItems, index, 0L)
        activeController.prepare()
        if (pendingShouldPlay) activeController.play() else activeController.pause()
    }
    fun toggle() {
        val activeController = controller
        if (activeController == null) {
            pendingShouldPlay = !pendingShouldPlay
            return
        }
        if (activeController.isPlaying) activeController.pause() else activeController.play()
    }
    fun stop() { controller?.stop() }
    /** Mutes local speaker output while listening, preventing the current station from keeping VAD active. */
    fun beginVoiceCapture() {
        val activeController = controller ?: return
        if (volumeBeforeVoiceCapture == null) {
            volumeBeforeVoiceCapture = activeController.volume
            activeController.volume = 0f
        }
    }
    fun endVoiceCapture() {
        val previousVolume = volumeBeforeVoiceCapture ?: return
        controller?.volume = previousVolume
        volumeBeforeVoiceCapture = null
    }
    fun skipToPrevious() { controller?.seekToPreviousMediaItem() }
    fun skipToNext() { controller?.seekToNextMediaItem() }
    fun retry() {
        _state.value = _state.value.copy(error = null)
        controller?.apply { prepare(); play() }
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        // Keep a stable, actionable state even if Media3 clears playerError during its reset.
        if (awaitingVoiceStationStart) _state.value.station?.let { unavailableVoiceStations.markUnavailable(it.id) }
        awaitingVoiceStationStart = false
        _state.value = _state.value.copy(error = "Couldn't connect to this station")
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publish()
    private fun mediaItem(station: Station) = MediaItem.Builder().setUri(station.streamUrl).setMediaId(station.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtworkUri(station.faviconUrl?.let(android.net.Uri::parse)).build()).build()
    private fun publish() { controller?.let { active ->
        val item = active.currentMediaItem
        val current = queue.firstOrNull { it.id == item?.mediaId } ?: _state.value.station
        val metadata = active.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() && it != current?.name }
        if (active.isPlaying && awaitingVoiceStationStart && current != null) {
            unavailableVoiceStations.markAvailable(current.id)
            awaitingVoiceStationStart = false
        }
        _state.value = _state.value.copy(
            station = current,
            isPlaying = active.isPlaying,
            error = active.playerError?.let { "Couldn't connect to this station" } ?: _state.value.error,
            streamTitle = title,
            streamArtist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            canSkipPrevious = active.hasPreviousMediaItem(),
            canSkipNext = active.hasNextMediaItem(),
        )
    } }
    fun release() { endVoiceCapture(); controller?.removeListener(this); MediaController.releaseFuture(future) }

    private data class PendingPlay(val station: Station, val stations: List<Station>, val fromVoiceResult: Boolean)
}
