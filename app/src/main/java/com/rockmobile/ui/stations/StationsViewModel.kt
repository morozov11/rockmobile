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

    /** Displays the ranked Rockserver candidates from a completed voice request. */
    fun showVoiceCandidates(candidates: List<Station>) {
        val content = _state.value as? StationsUiState.Content ?: return
        if (candidates.isEmpty()) return
        _state.value = content.copy(
            catalogue = StationCatalogue(candidates, content.catalogue.source),
            filters = StationFilters(),
        )
    }
}
