package com.rockmobile.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rockmobile.data.repository.CatalogueLoadResult
import com.rockmobile.data.repository.StationRepository
import com.rockmobile.domain.model.StationCatalogue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

sealed interface StationsUiState {
    data object Loading : StationsUiState
    data class Content(val catalogue: StationCatalogue, val fallbackReason: String? = null) : StationsUiState
    data class Error(val message: String) : StationsUiState
}

/** Owns only catalogue loading state; playback is intentionally delegated to MediaSession infrastructure. */
class StationsViewModel(private val repository: StationRepository) : ViewModel() {
    private val _state = MutableStateFlow<StationsUiState>(StationsUiState.Loading)
    val state: StateFlow<StationsUiState> = _state.asStateFlow()
    init { retryRockserver() }
    fun retryRockserver() = viewModelScope.launch {
        _state.value = StationsUiState.Loading
        // HttpURLConnection is blocking; keep it off the Compose/Main dispatcher.
        val result = withContext(Dispatchers.IO) { repository.loadCatalogue() }
        _state.value = when (result) {
            is CatalogueLoadResult.Success -> StationsUiState.Content(result.catalogue)
            is CatalogueLoadResult.Fallback -> StationsUiState.Content(result.catalogue, "Rockserver unavailable — using built-in station catalogue")
            is CatalogueLoadResult.Fatal -> StationsUiState.Error("Could not load either Rockserver or the built-in catalogue")
        }
    }
}
