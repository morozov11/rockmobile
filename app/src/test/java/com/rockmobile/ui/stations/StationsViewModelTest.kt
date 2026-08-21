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
        val model = StationsViewModel(repository(remote = { listOf(station) }, local = { emptyList() }), dispatcher)
        assertTrue(model.state.value is StationsUiState.Loading); runCurrent()
        assertEquals(CatalogueSource.ROCKSERVER, (model.state.value as StationsUiState.Content).catalogue.source)
    }
    @Test fun loading_transitionsToFallbackAndThenRetryRemote() = runTest(dispatcher) {
        var online = false
        val model = StationsViewModel(repository(remote = { if (online) listOf(station) else error("offline") }, local = { listOf(station) }), dispatcher)
        runCurrent(); assertEquals(CatalogueSource.BUNDLED, (model.state.value as StationsUiState.Content).catalogue.source)
        online = true; model.retryRockserver(); runCurrent()
        assertEquals(CatalogueSource.ROCKSERVER, (model.state.value as StationsUiState.Content).catalogue.source)
    }
    @Test fun loading_transitionsToFatalError() = runTest(dispatcher) {
        val model = StationsViewModel(repository(remote = { error("offline") }, local = { error("asset broken") }), dispatcher)
        runCurrent(); assertTrue(model.state.value is StationsUiState.Error)
    }
    @Test fun filters_matchNameGenreCountryAndLanguage_caseInsensitively() {
        val rock = Station("rock", "Northern Rock", "https://example.test/rock", tags = listOf("Hard Rock"), country = "Finland", language = "English")
        val jazz = Station("jazz", "Paris Jazz", "https://example.test/jazz", tags = listOf("Jazz"), country = "France", language = "French")
        assertEquals(listOf(rock), filterStations(listOf(rock, jazz), StationFilters(query = "northern", genre = "hard rock", country = "FINLAND", language = "english")))
    }
    @Test fun filters_returnEmptyForNoMatch_andIgnoreMissingOptionalFields() {
        val station = Station("id", "Rock", "https://example.test/live")
        assertTrue(filterStations(listOf(station), StationFilters(query = "jazz")).isEmpty())
        assertTrue(filterStations(listOf(station), StationFilters(country = "USA")).isEmpty())
        assertEquals(listOf(station), filterStations(listOf(station), StationFilters()))
    }
    @Test fun updateFilters_recalculatesContentWithoutReloadingCatalogue() = runTest(dispatcher) {
        val model = StationsViewModel(repository(remote = { listOf(station) }, local = { emptyList() }), dispatcher)
        runCurrent()
        model.updateFilters { it.copy(query = "missing") }
        assertTrue((model.state.value as StationsUiState.Content).stations.isEmpty())
    }
    @Test fun voiceCandidates_replaceVisibleList_andClearOldFilters() = runTest(dispatcher) {
        val other = Station("other", "Other", "https://example.test/other")
        val model = StationsViewModel(repository(remote = { listOf(station, other) }, local = { emptyList() }), dispatcher)
        runCurrent(); model.updateFilters { it.copy(query = "missing") }
        model.showVoiceCandidates(listOf(other))
        val content = model.state.value as StationsUiState.Content
        assertEquals(listOf(other), content.stations)
        assertEquals(StationFilters(), content.filters)
    }
    @Test fun unavailableVoiceCandidate_isMovedToEndUsingLocalMemory() = runTest(dispatcher) {
        val unavailable = Station("offline", "Offline", "https://example.test/offline")
        val available = Station("online", "Online", "https://example.test/online")
        val model = StationsViewModel(
            repository(remote = { listOf(available) }, local = { emptyList() }),
            dispatcher,
            unavailableVoiceStationIds = { setOf(unavailable.id) },
        )
        runCurrent()
        val stationToPlay = model.showVoiceCandidates(listOf(unavailable, available))
        assertEquals(listOf(available, unavailable), (model.state.value as StationsUiState.Content).stations)
        assertEquals(available, stationToPlay)
    }
    private fun repository(remote: suspend () -> List<Station>, local: suspend () -> List<Station>) = StationRepository(
        object : RemoteStationSource { override suspend fun load() = remote() }, object : LocalStationSource { override suspend fun load() = local() },
    )
}
