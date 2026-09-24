package com.rockmobile

import com.rockmobile.devicecontrol.LivePlaybackStatusUi
import com.rockmobile.devicecontrol.LiveTargetPresentation
import com.rockmobile.domain.model.Station
import com.rockmobile.playback.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackTransferTest {
    private val transfer = LocalPlaybackTransfer("phone-station", "desktop", "desktop-station")
    private val local = PlaybackState(station = Station("phone-station", "Phone", "https://example.test/phone"), isPlaying = true)

    @Test fun phone_stops_only_after_the_requested_station_is_confirmed_playing_on_the_target() {
        assertFalse(localTransferConfirmed(transfer, local, presentation(LivePlaybackStatusUi.Buffering, "desktop-station")))
        assertFalse(localTransferConfirmed(transfer, local, presentation(LivePlaybackStatusUi.Playing, "other-station")))
        assertTrue(localTransferConfirmed(transfer, local, presentation(LivePlaybackStatusUi.Playing, "desktop-station")))
    }

    private fun presentation(status: LivePlaybackStatusUi, stationId: String) = LiveTargetPresentation(
        targetId = "desktop",
        status = status,
        confirmedStationId = stationId,
        requestedStationId = null,
        volumePercent = null,
        muted = null,
        volumeApplying = false,
        volumeDragging = false,
        failure = null,
    )
}
