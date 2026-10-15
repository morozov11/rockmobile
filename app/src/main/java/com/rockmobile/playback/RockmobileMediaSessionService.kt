package com.rockmobile.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rockmobile.MainActivity
import com.rockmobile.account.KeystoreCredentialStore
import com.rockmobile.account.NativeSessionManager
import com.rockmobile.account.RockserverAccountGateway
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Latest ICY StreamTitle of the current media item. Timed metadata does not cross the
 * MediaController boundary, and the merged `mediaMetadata` overwrites the stream title with
 * the MediaItem station name, so the service-side player publishes it here for the UI.
 */
object LiveIcyTitles {
    data class Entry(val mediaId: String, val title: String)

    private val _latest = MutableStateFlow<Entry?>(null)
    val latest: StateFlow<Entry?> = _latest.asStateFlow()

    fun publish(mediaId: String, title: String) {
        _latest.value = Entry(mediaId, title)
    }
}

/** Media3 owns notification, lock-screen and Bluetooth controls while this service owns the player lifecycle. */
@androidx.annotation.OptIn(UnstableApi::class)
class RockmobileMediaSessionService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)
        val sessions = NativeSessionManager(
            RockserverAccountGateway(RockserverApi(), settings::rockserverUrl),
            KeystoreCredentialStore(applicationContext),
        )
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())) { spec ->
            spec.withRequestHeaders(runBlocking {
                stationStreamHeaders(settings.rockserverUrl(), spec.uri.toString(), spec.httpRequestHeaders) {
                    sessions.currentSession()?.accessToken
                }
            })
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onMetadata(metadata: Metadata) {
                    var title: String? = null
                    for (index in 0 until metadata.length()) {
                        val entry = metadata[index]
                        if (entry is IcyInfo && !entry.title.isNullOrBlank()) { title = entry.title; break }
                    }
                    val streamTitle = title ?: return
                    val mediaId = currentMediaItem?.mediaId ?: return
                    LiveIcyTitles.publish(mediaId, streamTitle)
                }
            })
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(sessionActivity).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.run { player.release(); release() }; session = null; super.onDestroy() }
}
