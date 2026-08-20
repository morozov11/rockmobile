package com.rockmobile.data.repository

import com.rockmobile.data.stations.LocalStationSource
import com.rockmobile.data.stations.RemoteStationSource
import com.rockmobile.domain.model.CatalogueSource
import com.rockmobile.domain.model.StationCatalogue
import kotlinx.coroutines.CancellationException

/** Remote-first catalogue policy. Stream errors never reach this layer, so they cannot trigger fallback. */
class StationRepository(private val remote: RemoteStationSource, private val local: LocalStationSource) {
    suspend fun loadCatalogue(): CatalogueLoadResult = try {
        val remoteStations = remote.load()
        if (remoteStations.isEmpty()) throw EmptyCatalogueException("Rockserver returned an empty catalogue")
        CatalogueLoadResult.Success(StationCatalogue(remoteStations, CatalogueSource.ROCKSERVER))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (remoteFailure: Throwable) {
        try {
            val bundled = local.load()
            if (bundled.isEmpty()) throw EmptyCatalogueException("Bundled catalogue is empty")
            CatalogueLoadResult.Fallback(StationCatalogue(bundled, CatalogueSource.BUNDLED), remoteFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bundledFailure: Throwable) {
            CatalogueLoadResult.Fatal(remoteFailure, bundledFailure)
        }
    }
}

class EmptyCatalogueException(message: String) : IllegalStateException(message)
sealed interface CatalogueLoadResult {
    data class Success(val catalogue: StationCatalogue) : CatalogueLoadResult
    data class Fallback(val catalogue: StationCatalogue, val remoteFailure: Throwable) : CatalogueLoadResult
    data class Fatal(val remoteFailure: Throwable, val bundledFailure: Throwable) : CatalogueLoadResult
}
