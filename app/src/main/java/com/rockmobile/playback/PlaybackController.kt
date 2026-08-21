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
class PlaybackController(context: Context) : Player.Listener {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        context, SessionToken(context, ComponentName(context, RockmobileMediaSessionService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null
    private var queue: List<Station> = emptyList()
    private var pendingPlay: Pair<Station, List<Station>>? = null
    /** Desired state while the asynchronous MediaController connection is still pending. */
    private var pendingShouldPlay = true

    init {
        future.addListener({
            controller = future.get().also { it.addListener(this); pendingPlay?.let { (station, stations) -> play(station, stations) }; publish() }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(station: Station, stations: List<Station> = listOf(station)) {
        val playableStations = stations.ifEmpty { listOf(station) }
        // A deferred replay preserves a user toggle made before the MediaSession connected.
        val deferredReplay = controller != null && pendingPlay?.first?.id == station.id
        if (!deferredReplay) pendingShouldPlay = true
        val index = playableStations.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        queue = playableStations
        _state.value = _state.value.copy(station = station, error = null, streamTitle = null, streamArtist = null,
            canSkipPrevious = index > 0, canSkipNext = index < playableStations.lastIndex)
        val mediaItems = playableStations.map(::mediaItem)
        val activeController = controller
        if (activeController == null) { pendingPlay = station to playableStations; pendingShouldPlay = true; return }
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
    fun skipToPrevious() { controller?.seekToPreviousMediaItem() }
    fun skipToNext() { controller?.seekToNextMediaItem() }
    fun retry() { controller?.apply { prepare(); play() } }
    override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { _state.value = _state.value.copy(error = error.message ?: "Playback failed") }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publish()
    private fun mediaItem(station: Station) = MediaItem.Builder().setUri(station.streamUrl).setMediaId(station.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtworkUri(station.faviconUrl?.let(android.net.Uri::parse)).build()).build()
    private fun publish() { controller?.let { active ->
        val item = active.currentMediaItem
        val current = queue.firstOrNull { it.id == item?.mediaId } ?: _state.value.station
        val metadata = active.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() && it != current?.name }
        _state.value = _state.value.copy(
            station = current,
            isPlaying = active.isPlaying,
            error = active.playerError?.message,
            streamTitle = title,
            streamArtist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            canSkipPrevious = active.hasPreviousMediaItem(),
            canSkipNext = active.hasNextMediaItem(),
        )
    } }
    fun release() { controller?.removeListener(this); MediaController.releaseFuture(future) }
}
