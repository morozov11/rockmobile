package com.rockmobile.data.dto

import com.rockmobile.domain.model.Station
import org.json.JSONArray
import org.json.JSONObject

/** Maps the stable fields in RockServer's `StationResult` OpenAPI schema to the domain model. */
internal fun parseRockserverStations(body: String): List<Station> {
    val stations = JSONObject(body).optJSONArray("stations")
        ?: throw IllegalArgumentException("Rockserver response has no stations array")
    return (0 until stations.length()).map { index -> parseStation(stations.getJSONObject(index)) }
}

private fun parseStation(json: JSONObject): Station {
    fun required(name: String): String = json.optString(name).trim().also {
        require(it.isNotEmpty()) { "Station is missing $name" }
    }
    val streamUrl = required("stream_url")
    require(streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) { "Invalid stream URL" }
    return Station(
        id = required("id"), name = required("name"), streamUrl = streamUrl,
        tags = json.optJSONArray("tags").toStringList(),
        country = json.optionalString("country_code"), language = json.optionalString("language"),
        codec = json.optionalString("codec"), bitrateKbps = json.optInt("bitrate_kbps").takeIf { it > 0 },
        homepageUrl = json.optionalString("homepage_url"), faviconUrl = json.optionalString("favicon_url"),
    )
}

private fun JSONArray?.toStringList(): List<String> = this?.let { array ->
    (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
}.orEmpty()

private fun JSONObject.optionalString(name: String): String? = optString(name).trim().takeIf(String::isNotEmpty)
