package com.rockmobile.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rockmobile.data.repository.CatalogueLoadResult
import com.rockmobile.data.repository.StationRepository
import com.rockmobile.data.repository.StationSearchResult
import com.rockmobile.domain.model.StationCatalogue
import com.rockmobile.domain.model.Station
import com.rockmobile.domain.model.StationFilterOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class StationFilters(
    val query: String = "",
    val genre: String? = null,
    val country: String? = null,
    val language: String? = null,
)

/** Filters only use fields that exist in [Station], so they work for both catalogue sources. */
fun filterStations(stations: List<Station>, filters: StationFilters) = stations.filter { station ->
    val queryTerms = filters.query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    val searchableText = buildString {
        append(station.name).append(' ')
        append(station.tags.joinToString(" ")).append(' ')
        append(station.country.orEmpty()).append(' ')
        append(station.language.orEmpty())
    }.lowercase()
    queryTerms.all(searchableText::contains) &&
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
        val filterOptions: StationFilterOptions? = null,
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
    private var searchJob: Job? = null
    private var filterOptionsJob: Job? = null
    val state: StateFlow<StationsUiState> = _state.asStateFlow()
    init { retryRockserver() }
    fun retryRockserver() = viewModelScope.launch {
        searchJob?.cancel()
        filterOptionsJob?.cancel()
        _state.value = StationsUiState.Loading
        val result = withContext(ioDispatcher) { repository.loadCatalogue() }
        _state.value = when (result) {
            is CatalogueLoadResult.Success -> StationsUiState.Content(result.catalogue)
            is CatalogueLoadResult.Fallback -> StationsUiState.Content(result.catalogue, "Using the backup offline station catalogue")
            is CatalogueLoadResult.Fatal -> StationsUiState.Error("Could not load either Rockserver or the built-in catalogue")
        }
        if (_state.value is StationsUiState.Content) refreshFilterOptions()
    }

    fun updateFilters(transform: (StationFilters) -> StationFilters) {
        val content = _state.value as? StationsUiState.Content ?: return
        val filters = transform(content.filters)
        if (filters == content.filters) return
        _state.value = content.copy(filters = filters)
        searchJob?.cancel()
        val hasSearchRequest = filters.query.trim().isNotEmpty() || filters.genre != null || filters.country != null || filters.language != null
        if (!hasSearchRequest) {
            if (content.filters.query.trim().isNotEmpty() || content.filters.genre != null || content.filters.country != null || content.filters.language != null || content.catalogue.source == com.rockmobile.domain.model.CatalogueSource.ROCKSERVER) {
                refreshAfterSearchCleared(filters)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(250)
            val result = withContext(ioDispatcher) {
                repository.search(filters.query, filters.genre, filters.country, filters.language)
            }
            val current = _state.value as? StationsUiState.Content ?: return@launch
            if (current.filters != filters) return@launch
            if (result is StationSearchResult.Success) {
                _state.value = current.copy(catalogue = result.catalogue, fallbackReason = null)
            }
        }
    }

    private fun refreshFilterOptions() {
        filterOptionsJob?.cancel()
        filterOptionsJob = viewModelScope.launch {
            val options = withContext(ioDispatcher) { repository.loadFilterOptions() } ?: return@launch
            val current = _state.value as? StationsUiState.Content ?: return@launch
            _state.value = current.copy(filterOptions = options)
        }
    }

    private fun refreshAfterSearchCleared(filters: StationFilters) {
        searchJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { repository.loadCatalogue() }
            val current = _state.value as? StationsUiState.Content ?: return@launch
            if (current.filters != filters) return@launch
            _state.value = when (result) {
                is CatalogueLoadResult.Success -> current.copy(catalogue = result.catalogue)
                is CatalogueLoadResult.Fallback -> current.copy(catalogue = result.catalogue, fallbackReason = "Using the built-in station catalogue")
                is CatalogueLoadResult.Fatal -> current
            }
        }
    }

    /**
     * Displays ranked Rockserver candidates and returns the first stream not known to be unavailable.
     * The returned station is the only candidate that voice auto-play may start.
     */
    fun showVoiceCandidates(candidates: List<Station>): Station? {
        if (candidates.isEmpty()) return null
        searchJob?.cancel()
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
