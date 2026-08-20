package com.rockmobile.domain.model

/** Source that supplied the catalogue currently shown to the listener. */
enum class CatalogueSource { ROCKSERVER, BUNDLED }

/** Playback-ready station independent of a transport DTO or Android UI type. */
data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tags: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val homepageUrl: String? = null,
    val faviconUrl: String? = null,
)

data class StationCatalogue(val stations: List<Station>, val source: CatalogueSource)
