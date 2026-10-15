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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val rockserverUrl: () -> String,
) : Player.Listener {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        context, SessionToken(context, ComponentName(context, RockmobileMediaSessionService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null
    private var queue: List<Station> = emptyList()
    /** Catalogue-station view used to restore the current station after an activity restart. */
    private var knownStations: List<Station> = emptyList()
    private var pendingPlay: PendingPlay? = null
    private var volumeBeforeVoiceCapture: Float? = null
    private var awaitingVoiceStationStart = false
    /** Desired state while the asynchronous MediaController connection is still pending. */
    private var pendingShouldPlay = true
    /** ICY titles arrive as service-side timed metadata, not through the merged controller metadata. */
    private val icyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        future.addListener({
            controller = future.get().also { it.addListener(this); pendingPlay?.let { play(it.station, it.stations, it.fromVoiceResult) }; publish() }
        }, ContextCompat.getMainExecutor(context))
        icyScope.launch {
            LiveIcyTitles.latest.collect { entry ->
                if (entry != null && entry.mediaId == _state.value.station?.id) {
                    _state.value = _state.value.copy(streamTitle = entry.title)
                }
            }
        }
    }

    fun play(station: Station, stations: List<Station> = listOf(station), fromVoiceResult: Boolean = false) {
        val baseStations = stations.ifEmpty { listOf(station) }
        val playableStations = if (baseStations.any { it.id == station.id }) baseStations else listOf(station) + baseStations
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
    fun stop() {
        pendingPlay = null
        pendingShouldPlay = false
        awaitingVoiceStationStart = false
        controller?.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }
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
    /** Lets publish() resolve the playing station from the catalogue after an activity restart. */
    fun adoptStations(stations: List<Station>) {
        knownStations = stations
        if (_state.value.station == null) publish()
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        // Keep a stable, actionable state even if Media3 clears playerError during its reset.
        val needsAccount = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        if (awaitingVoiceStationStart && !needsAccount) _state.value.station?.let { unavailableVoiceStations.markUnavailable(it.id) }
        awaitingVoiceStationStart = false
        _state.value = _state.value.copy(error = playbackErrorMessage(error.errorCode))
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publish()
    private fun mediaItem(station: Station) = MediaItem.Builder().setUri(stationStreamUrl(rockserverUrl(), station.id)).setMediaId(station.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtworkUri(station.faviconUrl?.let(android.net.Uri::parse)).build()).build()

    /**
     * After the system recreates the activity while the service keeps playing, queue and
     * catalogue are empty and a controller-side MediaItem carries no stream URI; the id,
     * title and artwork are still enough for a display-only station so the mini-player and
     * transport controls return.
     */
    private fun stationFromMediaItem(item: MediaItem): Station = Station(
        id = item.mediaId,
        name = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId,
        streamUrl = item.requestMetadata?.mediaUri?.toString() ?: "",
        faviconUrl = item.mediaMetadata.artworkUri?.toString(),
    )
    private fun publish() { controller?.let { active ->
        val item = active.currentMediaItem
        val current = queue.firstOrNull { it.id == item?.mediaId }
            ?: item?.let { knownStations.firstOrNull { it.id == item.mediaId } }
            ?: item?.let(::stationFromMediaItem)
            ?: _state.value.station
        val metadata = active.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() && it != current?.name }
        if (active.isPlaying && awaitingVoiceStationStart && current != null) {
            unavailableVoiceStations.markAvailable(current.id)
            awaitingVoiceStationStart = false
        }
        _state.value = _state.value.copy(
            station = current,
            isPlaying = active.isPlaying,
            error = active.playerError?.let { playbackErrorMessage(it.errorCode) } ?: _state.value.error,
            streamTitle = title ?: LiveIcyTitles.latest.value?.takeIf { it.mediaId == current?.id }?.title,
            streamArtist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            canSkipPrevious = active.hasPreviousMediaItem(),
            canSkipNext = active.hasNextMediaItem(),
        )
    } }
    fun release() { icyScope.cancel(); endVoiceCapture(); controller?.removeListener(this); MediaController.releaseFuture(future) }

    private data class PendingPlay(val station: Station, val stations: List<Station>, val fromVoiceResult: Boolean)
}

internal fun playbackErrorMessage(errorCode: Int): String =
    if (errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        "Sign in to listen through RockServer"
    else "Couldn't connect to this station"
