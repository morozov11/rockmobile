package com.rockmobile.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rockmobile.data.repository.CatalogueLoadResult
import com.rockmobile.data.repository.StationRepository
import com.rockmobile.domain.model.StationCatalogue
import com.rockmobile.domain.model.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher

data class StationFilters(
    val query: String = "",
    val genre: String? = null,
    val country: String? = null,
    val language: String? = null,
)

/** Filters only use fields that exist in [Station], so they work for both catalogue sources. */
fun filterStations(stations: List<Station>, filters: StationFilters) = stations.filter { station ->
    station.name.contains(filters.query.trim(), ignoreCase = true) &&
        (filters.genre == null || station.tags.any { it.equals(filters.genre, ignoreCase = true) }) &&
        (filters.country == null || station.country.equals(filters.country, ignoreCase = true)) &&
        (filters.language == null || station.language.equals(filters.language, ignoreCase = true))
}

/** Keeps server ranking intact while moving locally known-broken voice streams to the end. */
fun rankVoiceCandidates(candidates: List<Station>, unavailableIds: Set<String>): List<Station> =
    candidates.sortedBy { station -> if (station.id in unavailableIds) 1 else 0 }

sealed interface StationsUiState {
    data object Loading : StationsUiState
    data class Content(
        val catalogue: StationCatalogue,
        val fallbackReason: String? = null,
        val filters: StationFilters = StationFilters(),
    ) : StationsUiState {
        val stations get() = filterStations(catalogue.stations, filters)
    }
    data class Error(val message: String) : StationsUiState
}

/** Owns only catalogue loading state; playback is intentionally delegated to MediaSession infrastructure. */
class StationsViewModel(
    private val repository: StationRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val unavailableVoiceStationIds: () -> Set<String> = { emptySet() },
) : ViewModel() {
    private val _state = MutableStateFlow<StationsUiState>(StationsUiState.Loading)
    val state: StateFlow<StationsUiState> = _state.asStateFlow()
    init { retryRockserver() }
    fun retryRockserver() = viewModelScope.launch {
        _state.value = StationsUiState.Loading
        // HttpURLConnection is blocking; keep it off the Compose/Main dispatcher.
        val result = withContext(ioDispatcher) { repository.loadCatalogue() }
        _state.value = when (result) {
            is CatalogueLoadResult.Success -> StationsUiState.Content(result.catalogue)
            is CatalogueLoadResult.Fallback -> StationsUiState.Content(result.catalogue, "Rockserver unavailable — using built-in station catalogue")
            is CatalogueLoadResult.Fatal -> StationsUiState.Error("Could not load either Rockserver or the built-in catalogue")
        }
    }

    fun updateFilters(transform: (StationFilters) -> StationFilters) {
        val content = _state.value as? StationsUiState.Content ?: return
        _state.value = content.copy(filters = transform(content.filters))
    }

    /**
     * Displays ranked Rockserver candidates and returns the first stream not known to be unavailable.
     * The returned station is the only candidate that voice auto-play may start.
     */
    fun showVoiceCandidates(candidates: List<Station>): Station? {
        if (candidates.isEmpty()) return null
        val unavailableIds = unavailableVoiceStationIds()
        val rankedCandidates = rankVoiceCandidates(candidates, unavailableIds)
        val content = _state.value as? StationsUiState.Content
        if (content != null) {
            _state.value = content.copy(
                catalogue = StationCatalogue(rankedCandidates, content.catalogue.source),
                filters = StationFilters(),
            )
        }
        return rankedCandidates.firstOrNull { it.id !in unavailableIds }
    }
}
