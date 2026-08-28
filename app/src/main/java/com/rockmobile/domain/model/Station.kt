package com.rockmobile.domain.model

/** Source that supplied the catalogue currently shown to the listener. */
enum class CatalogueSource { ROCKSERVER, EXTENDED, BUNDLED }

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

/** Complete filter vocabulary obtained from the server, or derived from the local catalogue offline. */
data class StationFilterOptions(
    val genres: List<String>,
    val countries: List<String>,
    val languages: List<String>,
) {
    companion object {
        fun from(stations: List<Station>): StationFilterOptions = StationFilterOptions(
            genres = stations.flatMap { it.tags }.distinctBy(String::lowercase).sortedBy(String::lowercase),
            countries = stations.mapNotNull { it.country }.distinctBy(String::lowercase).sortedBy(String::lowercase),
            languages = stations.mapNotNull { it.language }.distinctBy(String::lowercase).sortedBy(String::lowercase),
        )
    }
}
