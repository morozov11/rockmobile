package com.rockmobile.data.stations

import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackLocalStationSourceTest {
    private val station = Station("baseline", "Baseline", "https://example.test/stream")

    @Test fun invalidExtendedSnapshot_usesIndependentBaseline() = runTest {
        val source = FallbackLocalStationSource(failingExtended(), baseline(listOf(station)))
        assertEquals(listOf(station), source.load())
        assertEquals(CatalogueSource.BUNDLED, source.catalogueSource)
    }

    @Test fun validExtendedSnapshot_staysSelectedForOfflineSearch() = runTest {
        val extendedStation = station.copy(id = "extended")
        val source = FallbackLocalStationSource(searchableExtended(extendedStation), baseline(listOf(station)))
        assertEquals(listOf(extendedStation), source.load())
        assertEquals(CatalogueSource.EXTENDED, source.catalogueSource)
        assertEquals(listOf(extendedStation), source.search("extended", null, null, null))
    }

    @Test fun cancellation_isNotConvertedToBaselineFallback() = runTest {
        try {
            FallbackLocalStationSource(object : LocalStationSource {
                override val catalogueSource = CatalogueSource.EXTENDED
                override suspend fun load(): List<Station> = throw CancellationException()
            }, baseline(listOf(station))).load()
            throw AssertionError("cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    private fun failingExtended() = object : LocalStationSource {
        override val catalogueSource = CatalogueSource.EXTENDED
        override suspend fun load(): List<Station> = error("checksum mismatch")
    }
    private fun searchableExtended(station: Station) = object : LocalStationSource {
        override val catalogueSource = CatalogueSource.EXTENDED
        override suspend fun load() = listOf(station)
        override suspend fun search(query: String, genre: String?, country: String?, language: String?) = listOf(station)
    }
    private fun baseline(stations: List<Station>) = object : LocalStationSource {
        override suspend fun load() = stations
    }
}
