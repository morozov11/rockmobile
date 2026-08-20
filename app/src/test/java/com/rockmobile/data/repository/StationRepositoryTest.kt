package com.rockmobile.data.repository

import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StationRepositoryTest {
    private val station = Station("id", "Rock", "https://example.test/live")
    @Test fun remoteSuccess_usesRockserver() = runTest { assertEquals(CatalogueSource.ROCKSERVER, (StationRepository(remote(listOf(station)), local(emptyList())).loadCatalogue() as CatalogueLoadResult.Success).catalogue.source) }
    @Test fun remoteFailures_fallBackToBundled() = runTest {
        listOf(IllegalStateException(), EmptyCatalogueException("empty")).forEach { failure ->
            val result = StationRepository(remote(error = failure), local(listOf(station))).loadCatalogue() as CatalogueLoadResult.Fallback
            assertEquals(CatalogueSource.BUNDLED, result.catalogue.source)
        }
    }
    @Test fun bothFailures_areFatal() = runTest { assert(StationRepository(remote(error = IllegalStateException()), local(error = IllegalStateException())).loadCatalogue() is CatalogueLoadResult.Fatal) }
    @Test fun cancellation_isNotConvertedToFallback() = runTest {
        try { StationRepository(remote(error = CancellationException()), local(listOf(station))).loadCatalogue(); throw AssertionError("cancellation swallowed") } catch (_: CancellationException) {}
    }
    private fun remote(items: List<Station> = emptyList(), error: Throwable? = null) = object : RemoteStationSource { override suspend fun load() = error?.let { throw it } ?: items }
    private fun local(items: List<Station> = emptyList(), error: Throwable? = null) = object : LocalStationSource { override suspend fun load() = error?.let { throw it } ?: items }
}
