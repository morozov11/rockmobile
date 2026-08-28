package com.rockmobile.ui.stations

import com.rockmobile.data.repository.StationRepository
import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test fun loading_usesLocalCatalogue_withoutCallingRemoteSearch() = runTest(dispatcher) {
        var remoteCalled = false
        val model = StationsViewModel(repository(local = { listOf(station) }, remoteSearch = { remoteCalled = true; listOf(station) }), dispatcher)
        assertTrue(model.state.value is StationsUiState.Loading); runCurrent()
        assertEquals(CatalogueSource.BUNDLED, (model.state.value as StationsUiState.Content).catalogue.source)
        assertTrue(!remoteCalled)
    }
    @Test fun loading_usesFallbackWhenPrimaryLocalCatalogueFails() = runTest(dispatcher) {
        val primary = object : LocalStationSource { override suspend fun load(): List<Station> = error("primary broken") }
        val fallback = object : LocalStationSource { override suspend fun load() = listOf(station) }
        val model = StationsViewModel(StationRepository(object : RemoteStationSource { override suspend fun search(query: String) = null }, primary, fallback), dispatcher)
        runCurrent(); assertEquals(CatalogueSource.BUNDLED, (model.state.value as StationsUiState.Content).catalogue.source)
    }
    @Test fun loading_transitionsToFatalError() = runTest(dispatcher) {
        val model = StationsViewModel(repository(local = { error("asset broken") }), dispatcher)
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
        val model = StationsViewModel(repository(local = { listOf(station) }), dispatcher)
        runCurrent()
        model.updateFilters { it.copy(query = "missing") }
        assertTrue((model.state.value as StationsUiState.Content).stations.isEmpty())
    }
    @Test fun textSearch_usesRemoteOnlyForNonBlankQuery() = runTest(dispatcher) {
        val jazz = Station("jazz", "Quiet Jazz", "https://example.test/jazz", tags = listOf("jazz"))
        var requestedQuery: String? = null
        val model = StationsViewModel(
            repository(
                local = { listOf(station) },
                remoteSearch = { query -> requestedQuery = query; listOf(jazz) },
            ),
            dispatcher,
        )
        runCurrent()
        model.updateFilters { it.copy(query = "jazz") }
        advanceTimeBy(250); runCurrent()
        assertEquals("jazz", requestedQuery)
        assertEquals(listOf(jazz), (model.state.value as StationsUiState.Content).stations)
    }

    @Test fun selectingGenre_withNoText_usesRemoteSearchAndKeepsCompleteOptions() = runTest(dispatcher) {
        val jazz = Station("jazz", "Quiet Jazz", "https://example.test/jazz", tags = listOf("jazz"), country = "FR", language = "fr")
        var requestedQuery: String? = null
        val remote = object : RemoteStationSource {
            override suspend fun search(query: String): List<Station> {
                requestedQuery = query
                return listOf(jazz)
            }
            override suspend fun loadCatalogue() = listOf(
                station.copy(tags = listOf("rock"), country = "US", language = "en"),
                jazz,
            )
        }
        val model = StationsViewModel(
            StationRepository(remote, object : LocalStationSource { override suspend fun load() = listOf(station) }),
            dispatcher,
        )
        runCurrent()
        assertTrue((model.state.value as StationsUiState.Content).filterOptions?.genres?.contains("jazz") == true)
        model.updateFilters { it.copy(genre = "jazz") }
        advanceTimeBy(250); runCurrent()
        assertEquals("jazz", requestedQuery)
        val content = model.state.value as StationsUiState.Content
        assertEquals(CatalogueSource.ROCKSERVER, content.catalogue.source)
        assertEquals(listOf(jazz), content.stations)
    }
    @Test fun voiceCandidates_replaceVisibleList_andClearOldFilters() = runTest(dispatcher) {
        val other = Station("other", "Other", "https://example.test/other")
        val model = StationsViewModel(repository(local = { listOf(station, other) }), dispatcher)
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
            repository(local = { listOf(available) }),
            dispatcher,
            unavailableVoiceStationIds = { setOf(unavailable.id) },
        )
        runCurrent()
        val stationToPlay = model.showVoiceCandidates(listOf(unavailable, available))
        assertEquals(listOf(available, unavailable), (model.state.value as StationsUiState.Content).stations)
        assertEquals(available, stationToPlay)
    }
    private fun repository(
        local: suspend () -> List<Station>,
        remoteSearch: suspend (String) -> List<Station>? = { null },
    ) = StationRepository(
        object : RemoteStationSource { override suspend fun search(query: String) = remoteSearch(query) },
        object : LocalStationSource { override suspend fun load() = local() },
    )
}
