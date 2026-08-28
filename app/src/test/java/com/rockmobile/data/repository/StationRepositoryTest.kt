package com.rockmobile.data.repository

import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class StationRepositoryTest {
    private val station = Station("id", "Rock", "https://example.test/live")
    @Test fun loadCatalogue_usesPrimaryLocalSource() = runTest {
        assertEquals(CatalogueSource.BUNDLED, (StationRepository(remote(), local(listOf(station))).loadCatalogue() as CatalogueLoadResult.Success).catalogue.source)
    }
    @Test fun primaryLocalFailure_fallsBackToOfflineSource() = runTest {
        listOf(IllegalStateException(), SocketTimeoutException(), EmptyCatalogueException("empty")).forEach { failure ->
            val result = StationRepository(remote(), local(error = failure), local(listOf(station))).loadCatalogue() as CatalogueLoadResult.Fallback
            assertEquals(CatalogueSource.BUNDLED, result.catalogue.source)
        }
    }
    @Test fun bothFailures_areFatal() = runTest { assert(StationRepository(remote(), local(error = IllegalStateException())).loadCatalogue() is CatalogueLoadResult.Fatal) }
    @Test fun cancellation_isNotConvertedToFallback() = runTest {
        try { StationRepository(remote(), local(error = CancellationException())).loadCatalogue(); throw AssertionError("cancellation swallowed") } catch (_: CancellationException) {}
    }
    @Test fun search_usesRemoteForExplicitQuery() = runTest {
        val result = StationRepository(remote(listOf(station)), local(emptyList())).search("rock", null, null, null)
        assertEquals(CatalogueSource.ROCKSERVER, (result as StationSearchResult.Success).catalogue.source)
    }
    @Test fun search_fallsBackToOfflineSearchWhenRemoteFails() = runTest {
        val result = StationRepository(remote(error = IOException("offline")), local(listOf(station))).search("rock", null, null, null)
        assertEquals(StationSearchResult.Unavailable, result)
    }

    @Test fun search_sendsSelectedFiltersAsNaturalLanguageToRockserver() = runTest {
        var requestedQuery: String? = null
        val remote = object : RemoteStationSource {
            override suspend fun search(query: String): List<Station> {
                requestedQuery = query
                return listOf(station)
            }
        }
        StationRepository(remote, local(listOf(station))).search("", "hard rock", "US", "en")
        assertEquals("hard rock from United States англоязычный", requestedQuery)
    }

    @Test fun loadFilterOptions_usesRemoteCatalogueWithoutReplacingStartList() = runTest {
        val remote = object : RemoteStationSource {
            override suspend fun search(query: String) = emptyList<Station>()
            override suspend fun loadCatalogue() = listOf(
                station.copy(tags = listOf("rock", "metal"), country = "US", language = "en"),
                station.copy(id = "other", tags = listOf("jazz"), country = "FR", language = "fr"),
            )
        }
        assertEquals(
            com.rockmobile.domain.model.StationFilterOptions(listOf("jazz", "metal", "rock"), listOf("FR", "US"), listOf("en", "fr")),
            StationRepository(remote, local(listOf(station))).loadFilterOptions(),
        )
    }

    @Test fun loadFilterOptions_degradesToLocalWhenServerUnavailable() = runTest {
        val remote = object : RemoteStationSource {
            override suspend fun search(query: String) = emptyList<Station>()
            override suspend fun loadCatalogue(): List<Station> = error("offline")
        }
        assertEquals(null, StationRepository(remote, local(listOf(station))).loadFilterOptions())
    }

    private fun remote(items: List<Station> = emptyList(), error: Throwable? = null) = object : RemoteStationSource { override suspend fun search(query: String) = error?.let { throw it } ?: items }
    private fun local(items: List<Station> = emptyList(), error: Throwable? = null) = object : LocalStationSource { override suspend fun load() = error?.let { throw it } ?: items }
}
