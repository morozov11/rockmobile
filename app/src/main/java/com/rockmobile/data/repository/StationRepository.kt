package com.rockmobile.data.repository

import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.StationCatalogue
import com.rockmobile.domain.model.Station
import com.rockmobile.domain.model.StationFilterOptions
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Local-first catalogue policy matching RockCast; RockServer handles discovery and filter searches. */
class StationRepository(
    private val remote: RemoteStationSource,
    private val primary: LocalStationSource,
    private val offlineSearch: LocalStationSource = primary,
) {
    suspend fun loadCatalogue(): CatalogueLoadResult = try {
        val primaryStations = primary.load()
        if (primaryStations.isEmpty()) throw EmptyCatalogueException("Local catalogue is empty")
        CatalogueLoadResult.Success(StationCatalogue(primaryStations, primary.catalogueSource))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (primaryFailure: Throwable) {
        try {
            val fallback = offlineSearch.load()
            if (fallback.isEmpty()) throw EmptyCatalogueException("Offline catalogue is empty")
            CatalogueLoadResult.Fallback(StationCatalogue(fallback, offlineSearch.catalogueSource), primaryFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bundledFailure: Throwable) {
            CatalogueLoadResult.Fatal(primaryFailure, bundledFailure)
        }
    }

    suspend fun search(query: String, genre: String?, country: String?, language: String?): StationSearchResult = try {
        val stations = remote.search(remoteSearchQuery(query, genre, country, language))
            ?: throw UnsupportedOperationException("Remote search is unavailable")
        StationSearchResult.Success(StationCatalogue(stations, CatalogueSource.ROCKSERVER))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        try {
            offlineSearch.load()
            offlineSearch.search(query, genre, country, language)?.let {
                StationSearchResult.Success(StationCatalogue(it, offlineSearch.catalogueSource))
            } ?: StationSearchResult.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            StationSearchResult.Unavailable
        }
    }

    /** Loads the server's complete public vocabulary without replacing the local start catalogue. */
    suspend fun loadFilterOptions(): StationFilterOptions? = try {
        remote.loadCatalogue()
            ?.takeIf { it.isNotEmpty() }
            ?.let(StationFilterOptions::from)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun remoteSearchQuery(query: String, genre: String?, country: String?, language: String?): String =
        buildList {
            query.trim().takeIf(String::isNotEmpty)?.let(::add)
            genre?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            country?.trim()?.takeIf(String::isNotEmpty)?.let { code ->
                val countryName = runCatching {
                    Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build().getDisplayCountry(Locale.ENGLISH)
                }.getOrDefault("")
                add("from ${countryName.ifBlank { code }}")
            }
            language?.trim()?.takeIf(String::isNotEmpty)?.let { code ->
                add(languageSearchTerm(code))
            }
        }.joinToString(" ")

    private fun languageSearchTerm(code: String): String = when (code.lowercase(Locale.ROOT)) {
        "en" -> "англоязычный"
        "ru" -> "русскоязычный"
        else -> runCatching {
            Locale.Builder().setLanguage(code.lowercase(Locale.ROOT)).build().getDisplayLanguage(Locale.ENGLISH)
        }.getOrDefault("").ifBlank { code }
    }
}

class EmptyCatalogueException(message: String) : IllegalStateException(message)
sealed interface CatalogueLoadResult {
    data class Success(val catalogue: StationCatalogue) : CatalogueLoadResult
    data class Fallback(val catalogue: StationCatalogue, val primaryFailure: Throwable) : CatalogueLoadResult
    data class Fatal(val primaryFailure: Throwable, val fallbackFailure: Throwable) : CatalogueLoadResult
}

sealed interface StationSearchResult {
    data class Success(val catalogue: StationCatalogue) : StationSearchResult
    data object Unavailable : StationSearchResult
}
