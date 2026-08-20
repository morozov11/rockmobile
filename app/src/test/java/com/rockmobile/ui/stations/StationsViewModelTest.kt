package com.rockmobile.ui.stations

import com.rockmobile.data.repository.StationRepository
import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val station = Station("id", "Rock", "https://example.test/live")
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loading_transitionsToRemoteSuccess() = runTest(dispatcher) {
        val model = StationsViewModel(repository(remote = { listOf(station) }, local = { emptyList() }))
        assertTrue(model.state.value is StationsUiState.Loading); runCurrent()
        assertEquals(CatalogueSource.ROCKSERVER, (model.state.value as StationsUiState.Content).catalogue.source)
    }
    @Test fun loading_transitionsToFallbackAndThenRetryRemote() = runTest(dispatcher) {
        var online = false
        val model = StationsViewModel(repository(remote = { if (online) listOf(station) else error("offline") }, local = { listOf(station) }))
        runCurrent(); assertEquals(CatalogueSource.BUNDLED, (model.state.value as StationsUiState.Content).catalogue.source)
        online = true; model.retryRockserver(); runCurrent()
        assertEquals(CatalogueSource.ROCKSERVER, (model.state.value as StationsUiState.Content).catalogue.source)
    }
    @Test fun loading_transitionsToFatalError() = runTest(dispatcher) {
        val model = StationsViewModel(repository(remote = { error("offline") }, local = { error("asset broken") }))
        runCurrent(); assertTrue(model.state.value is StationsUiState.Error)
    }
    private fun repository(remote: suspend () -> List<Station>, local: suspend () -> List<Station>) = StationRepository(
        object : RemoteStationSource { override suspend fun load() = remote() }, object : LocalStationSource { override suspend fun load() = local() },
    )
}
