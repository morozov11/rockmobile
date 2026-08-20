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

data class PlaybackState(val station: Station? = null, val isPlaying: Boolean = false, val error: String? = null)

/** Activity-scoped proxy to the service session; the service remains the sole ExoPlayer owner. */
class PlaybackController(context: Context) : Player.Listener {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        context, SessionToken(context, ComponentName(context, RockmobileMediaSessionService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null

    init { future.addListener({ controller = future.get().also { it.addListener(this); publish() } }, ContextCompat.getMainExecutor(context)) }
    fun play(station: Station) {
        _state.value = _state.value.copy(station = station, error = null)
        val item = MediaItem.Builder().setUri(station.streamUrl).setMediaId(station.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist(station.tags.joinToString()).build()).build()
        controller?.apply { setMediaItem(item); prepare(); play() }
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun stop() { controller?.stop() }
    override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { _state.value = _state.value.copy(error = error.message ?: "Playback failed") }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
    private fun publish() { controller?.let { _state.value = _state.value.copy(isPlaying = it.isPlaying) } }
    fun release() { controller?.removeListener(this); MediaController.releaseFuture(future) }
}
