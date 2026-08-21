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
    /** The reviewed stream declarations; [streamUrl] is always the primary entry for existing callers. */
    val streams: List<StationStream> = listOf(
        StationStream(id = "primary", url = streamUrl, codec = codec, bitrateKbps = bitrateKbps, primary = true),
    ),
)

/** Stable stream identity is scoped to its station. Playback deliberately uses only the primary stream. */
data class StationStream(
    val id: String,
    val url: String,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val primary: Boolean,
)

data class StationCatalogue(val stations: List<Station>, val source: CatalogueSource)
