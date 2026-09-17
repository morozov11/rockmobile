package com.rockmobile.devicecontrol

import com.rockmobile.domain.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer coverage of the ТЗ §4.4 state reconciliation matrix plus the absence,
 * revision-ordering, volume-gesture and catalogue-fallback rules that the live UI relies on.
 * No Android or coroutine dependencies are involved.
 */
class LivePlaybackReducerTest {

    private val target = "rockcast-living-room"
    private val other = "rockcast-kitchen"

    private fun confirmed(
        targetId: String = target,
        revision: Long,
        status: PlaybackStatus,
        stationId: String? = null,
        volume: Int? = null,
        muted: Boolean? = null,
    ) = ConfirmedDeviceState(
        targetId = targetId,
        stateRevision = revision,
        observedAt = "2026-09-17T12:00:00Z",
        status = status,
        stationId = stationId,
        volumeLevel = volume,
        muted = muted,
    )

    private fun playIntent(
        commandId: String = "cmd-1",
        stationId: String = "station-a",
        targetId: String = target,
        stateRevisionAtSend: Long = 3,
    ) = PendingPlaybackIntent(
        commandId = commandId,
        targetId = targetId,
        kind = PendingIntentKind.Play(stationId),
        stateRevisionAtSend = stateRevisionAtSend,
        sentAtMs = 1_000,
    )

    private fun stateWith(intent: PendingPlaybackIntent, confirmed: ConfirmedDeviceState? = null): LivePlaybackState =
        LivePlaybackState(targets = mapOf(intent.targetId to LiveTargetState(confirmed = confirmed, pending = intent)))

    // --- Matrix row: pendingIntent(A) + stateUpdate(A, buffering) -> Buffering(A) ---

    @Test
    fun `buffering echo of the requested station presents buffering`() {
        val state = stateWith(playIntent(), confirmed(revision = 3, status = PlaybackStatus.Stopped, stationId = "station-old"))
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 4, status = PlaybackStatus.Buffering, stationId = "station-a")),
        ).state
        val presentation = updated.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Buffering, presentation.status)
        assertEquals("station-a", presentation.confirmedStationId)
    }

    // --- Matrix row: pendingIntent(A) + stateUpdate(A, playing) -> Playing(A), intent cleared ---

    @Test
    fun `playing echo of the requested station clears the pending intent`() {
        val state = stateWith(playIntent())
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 4, status = PlaybackStatus.Playing, stationId = "station-a")),
        ).state
        val presentation = updated.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Playing, presentation.status)
        assertNull(updated.targets[target]!!.pending)
        assertNull(updated.overrideNotice)
    }

    // --- Matrix row: pendingIntent(A) + stateUpdate(B, rev newer) -> Playing(B) + override notice ---

    @Test
    fun `external override to another station cancels the intent immediately`() {
        val state = stateWith(playIntent())
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 5, status = PlaybackStatus.Playing, stationId = "station-b")),
        ).state
        val presentation = updated.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Playing, presentation.status)
        assertEquals("station-b", presentation.confirmedStationId)
        assertNull(updated.targets[target]!!.pending)
        assertEquals(target, updated.overrideNotice?.targetId)
        assertEquals("station-b", updated.overrideNotice?.stationId)
    }

    @Test
    fun `external stop cancels a pending play intent`() {
        val state = stateWith(playIntent())
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 4, status = PlaybackStatus.Stopped, stationId = "station-a")),
        ).state
        assertEquals(LivePlaybackStatusUi.Stopped, updated.presentTarget(target)!!.status)
        assertNull(updated.targets[target]!!.pending)
    }

    // --- Matrix row: terminal succeeded alone never means playing ---

    @Test
    fun `terminal success without a state echo keeps awaiting confirmation`() {
        val state = stateWith(playIntent())
        val updated = reduceLivePlayback(state, LivePlaybackEvent.CommandSucceeded("cmd-1")).state
        assertEquals(LivePlaybackStatusUi.AwaitingConfirmation, updated.presentTarget(target)!!.status)
        assertEquals("station-a", updated.presentTarget(target)!!.requestedStationId)
    }

    // --- Matrix row: failed command -> failure surfaced with the requested station for retry ---

    @Test
    fun `terminal failure surfaces a retryable failure for the requested station`() {
        val state = stateWith(playIntent())
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.CommandFailed("cmd-1", target, "target_unreachable"),
        ).state
        assertNull(updated.targets[target]!!.pending)
        assertEquals("station-a", updated.lastFailure?.stationId)
        assertEquals("target_unreachable", updated.lastFailure?.message)
        assertEquals(target, updated.lastFailure?.targetId)
    }

    @Test
    fun `rejected dispatch before any frame surfaces the blocking reason`() {
        val updated = reduceLivePlayback(
            LivePlaybackState(),
            LivePlaybackEvent.CommandRejected(target, "station-a", "Устройство offline"),
        ).state
        assertEquals("Устройство offline", updated.lastFailure?.message)
        assertEquals("station-a", updated.lastFailure?.stationId)
    }

    // --- Stop intent lifecycle ---

    @Test
    fun `stop intent confirms on the stopped echo`() {
        val state = LivePlaybackState(
            targets = mapOf(
                target to LiveTargetState(
                    confirmed = confirmed(revision = 6, status = PlaybackStatus.Playing, stationId = "station-a"),
                    pending = PendingPlaybackIntent("cmd-2", target, PendingIntentKind.Stop, 6, 1_000),
                ),
            ),
        )
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 7, status = PlaybackStatus.Stopped, stationId = "station-a")),
        ).state
        assertEquals(LivePlaybackStatusUi.Stopped, updated.presentTarget(target)!!.status)
        assertNull(updated.targets[target]!!.pending)
    }

    // --- Volume gesture isolation and single commit on release ---

    @Test
    fun `volume echoes do not move the slider during an active drag`() {
        var state = LivePlaybackState(
            targets = mapOf(target to LiveTargetState(confirmed = confirmed(revision = 5, status = PlaybackStatus.Playing, volume = 40))),
        )
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragStarted(target, 40)).state
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragged(77)).state
        state = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 6, status = PlaybackStatus.Playing, volume = 55)),
        ).state
        val presentation = state.presentTarget(target)!!
        assertTrue(presentation.volumeDragging)
        assertEquals(77, presentation.volumePercent)
    }

    @Test
    fun `drag release commits exactly one volume command and applying until echo`() {
        var state = LivePlaybackState(
            targets = mapOf(target to LiveTargetState(confirmed = confirmed(revision = 5, status = PlaybackStatus.Playing, volume = 40))),
        )
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragStarted(target, 40)).state
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragged(62)).state
        val transition = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragFinished)
        assertEquals(listOf(CommitVolume(target, 62)), transition.effects)

        val withIntent = reduceLivePlayback(
            transition.state,
            LivePlaybackEvent.VolumeIntentCreated(
                PendingVolumeIntent("cmd-9", target, 62, 5, 2_000),
            ),
        ).state
        val applying = withIntent.presentTarget(target)!!
        assertTrue(applying.volumeApplying)
        assertEquals(62, applying.volumePercent)

        val echoed = reduceLivePlayback(
            withIntent,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 6, status = PlaybackStatus.Playing, volume = 62)),
        ).state
        val settled = echoed.presentTarget(target)!!
        assertTrue(!settled.volumeApplying)
        assertEquals(62, settled.volumePercent)
        assertNull(echoed.pendingVolume)
    }

    @Test
    fun `drag release to the already confirmed level sends nothing`() {
        var state = LivePlaybackState(
            targets = mapOf(target to LiveTargetState(confirmed = confirmed(revision = 5, status = PlaybackStatus.Playing, volume = 62))),
        )
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragStarted(target, 62)).state
        state = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragged(62)).state
        val transition = reduceLivePlayback(state, LivePlaybackEvent.VolumeDragFinished)
        assertTrue(transition.effects.isEmpty())
    }

    // --- Absence, stale revisions and reconnect snapshot ---

    @Test
    fun `runtime state absence degrades to unknown without fabricated values`() {
        var state = LivePlaybackState(targets = mapOf(target to LiveTargetState()))
        state = reduceLivePlayback(state, LivePlaybackEvent.TargetStateUnknown(target)).state
        val presentation = state.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Unknown, presentation.status)
        assertNull(presentation.volumePercent)
        assertNull(presentation.confirmedStationId)
    }

    @Test
    fun `an older device revision never overwrites the confirmed projection`() {
        val state = LivePlaybackState(
            targets = mapOf(target to LiveTargetState(confirmed = confirmed(revision = 9, status = PlaybackStatus.Playing, stationId = "station-b"))),
        )
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 8, status = PlaybackStatus.Stopped, stationId = "station-a")),
        ).state
        val presentation = updated.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Playing, presentation.status)
        assertEquals("station-b", presentation.confirmedStationId)
    }

    @Test
    fun `a reconnect snapshot restores the confirmed state without command history`() {
        val state = LivePlaybackState()
        val updated = reduceLivePlayback(
            state,
            LivePlaybackEvent.TargetStateUpdated(confirmed(revision = 12, status = PlaybackStatus.Buffering, stationId = "station-b", volume = 71)),
        ).state
        val presentation = updated.presentTarget(target)!!
        assertEquals(LivePlaybackStatusUi.Buffering, presentation.status)
        assertEquals("station-b", presentation.confirmedStationId)
        assertEquals(71, presentation.volumePercent)
    }

    @Test
    fun `mini player auto selects a target confirmed playing`() {
        val state = LivePlaybackState(
            targets = mapOf(
                target to LiveTargetState(confirmed = confirmed(targetId = target, revision = 4, status = PlaybackStatus.Stopped)),
                other to LiveTargetState(confirmed = confirmed(targetId = other, revision = 7, status = PlaybackStatus.Playing, stationId = "station-b")),
            ),
        )
        val presentation = state.presentTarget(null)
        assertEquals(other, presentation?.targetId)
        assertEquals(LivePlaybackStatusUi.Playing, presentation?.status)
    }

    // --- Catalogue fallback stays separate from the playback fact ---

    @Test
    fun `a station missing from the catalogue degrades to a safe id label`() {
        val stations = listOf(Station(id = "station-a", name = "Station A", streamUrl = "https://stream.test/a"))
        assertEquals("Station A", stationDisplayTitle("station-a", stations))
        assertEquals("Станция station-z", stationDisplayTitle("station-z", stations))
        assertNull(stationDisplayTitle(null, stations))
    }
}
