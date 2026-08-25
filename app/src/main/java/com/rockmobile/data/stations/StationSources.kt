package com.rockmobile.data.stations

import android.content.res.AssetManager
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.dto.parseRockserverStations
import com.rockmobile.domain.model.Station
import com.rockmobile.domain.model.StationStream
import com.rockmobile.domain.model.CatalogueSource
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

interface RemoteStationSource { suspend fun load(): List<Station> }
interface LocalStationSource {
    val catalogueSource: CatalogueSource get() = CatalogueSource.BUNDLED
    suspend fun load(): List<Station>
    suspend fun search(query: String, genre: String?, country: String?, language: String?): List<Station>? = null
}

class RockserverStationSource(
    private val api: RockserverApi,
    private val baseUrl: () -> String,
    private val bearerToken: () -> String,
) : RemoteStationSource {
    override suspend fun load(): List<Station> = parseRockserverStations(api.search(baseUrl(), bearerToken()))
}

/** Reads the pinned, verified shared-catalog v1 snapshot. It never accesses the network. */
class RockcastAssetStationSource(
    private val assets: AssetManager,
    private val migrateLegacyIds: (Map<String, String>) -> Unit = {},
) : LocalStationSource {
    override suspend fun load(): List<Station> {
        val bytes = assets.open(ASSET_NAME).use { it.readBytes() }
        val stations = parseSharedCatalog(bytes, PINNED_CATALOG_VERSION, PINNED_SHA256)
        migrateLegacyIds(stations.flatMap { entry -> entry.legacyIds.map { legacyId -> legacyId to entry.station.id } }.toMap())
        return stations.map { it.station }
    }

    private companion object {
        const val ASSET_NAME = "stations.v1.json"
        const val PINNED_CATALOG_VERSION = "2026.08.2"
        const val PINNED_SHA256 = "3fa20dca94fc059bd433a47b9fba9bb6d5e5e1aa2957a5ffb58b2a7b20b1d74d"
    }
}

/** Uses the extended catalog when its whole release gate succeeds, otherwise preserves baseline radio. */
class FallbackLocalStationSource(
    private val extended: LocalStationSource,
    private val baseline: LocalStationSource,
) : LocalStationSource {
    private var active: LocalStationSource = baseline
    override val catalogueSource get() = active.catalogueSource
    override suspend fun load(): List<Station> = try {
        extended.load().also { active = extended }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        baseline.load().also { active = baseline }
    }
    override suspend fun search(query: String, genre: String?, country: String?, language: String?) =
        active.search(query, genre, country, language)
}

internal data class SharedCatalogStation(val station: Station, val legacyIds: List<String>)

/** Consumer-side semantic v1 validation. Unknown optional properties remain intentionally ignored. */
internal fun parseSharedCatalog(
    bytes: ByteArray,
    pinnedCatalogVersion: String,
    pinnedChecksum: String,
): List<SharedCatalogStation> {
    require(sha256(bytes) == pinnedChecksum) { "Bundled catalog checksum does not match pinned release" }
    val document = JSONObject(bytes.toString(Charsets.UTF_8))
    require(document.optInt("schemaVersion", -1) == 1) { "Unsupported catalog schema version" }
    require(document.requiredText("catalogVersion") == pinnedCatalogVersion) { "Bundled catalog version does not match pin" }
    require(document.optJSONArray("tombstones") != null) { "Catalog has no tombstones array" }
    val stations = document.optJSONArray("stations") ?: error("Catalog has no stations")
    require(stations.length() > 0) { "Catalog is empty" }
    val ids = mutableSetOf<String>()
    val legacyIds = mutableSetOf<String>()
    return (0 until stations.length()).map { index ->
        val json = stations.getJSONObject(index)
        val id = json.requiredText("id")
        require(STATION_ID.matches(id)) { "Invalid station ID: $id" }
        require(ids.add(id)) { "Duplicate station ID: $id" }
        json.optJSONArray("aliases").requiredStrings("aliases")
        val streams = json.optJSONArray("streams") ?: error("Station $id has no streams")
        val parsedStreams = (0 until streams.length()).map { streamIndex -> parseStream(id, streams.getJSONObject(streamIndex)) }
        require(parsedStreams.count { it.primary } == 1) { "Station $id must have exactly one primary stream" }
        require(parsedStreams.map { it.id }.toSet().size == parsedStreams.size) { "Station $id has duplicate stream IDs" }
        require(parsedStreams.map { it.url }.toSet().size == parsedStreams.size) { "Station $id has duplicate stream URLs" }
        val primary = parsedStreams.single { it.primary }
        val mappedLegacyIds = json.optJSONArray("legacyIds").requiredStrings("legacyIds")
        mappedLegacyIds.forEach { legacy -> require(LEGACY_ID.matches(legacy) && legacyIds.add(legacy)) { "Duplicate or invalid legacy ID: $legacy" } }
        SharedCatalogStation(Station(
            id = id, name = json.requiredText("name"), streamUrl = primary.url,
            tags = json.optJSONArray("tags").requiredStrings("tags"),
            country = json.nullableText("countryCode"), language = json.nullableText("language"),
            codec = primary.codec, bitrateKbps = primary.bitrateKbps,
            homepageUrl = json.nullableHttpUrl("homepageUrl"), faviconUrl = json.nullableHttpUrl("faviconUrl"),
            streams = parsedStreams,
        ), mappedLegacyIds)
    }
}

private fun parseStream(stationId: String, json: JSONObject): StationStream {
    val id = json.requiredText("id")
    require(STATION_ID.matches(id)) { "Invalid stream ID for $stationId" }
    val url = json.requiredText("url")
    require(isHttpUrl(url)) { "Invalid stream URL for $stationId" }
    val codec = json.optionalNullableText("codec")
    require(codec == null || CODEC.matches(codec)) { "Invalid codec for $stationId" }
    val bitrate = json.optionalNullableInt("bitrateKbps")
    require(bitrate == null || bitrate in 1..2000) { "Invalid bitrate for $stationId" }
    require(json.has("primary") && json.get("primary") is Boolean) { "Stream primary must be boolean" }
    return StationStream(id, url, codec, bitrate, json.getBoolean("primary"))
}

private fun JSONObject.requiredText(name: String): String = optString(name).trim().also { require(it.isNotEmpty()) { "Missing $name" } }
private fun JSONObject.nullableText(name: String): String? = if (has(name) && !isNull(name)) requiredText(name) else null
private fun JSONObject.optionalNullableText(name: String): String? = if (has(name) && !isNull(name)) requiredText(name) else null
private fun JSONObject.optionalNullableInt(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
private fun JSONObject.nullableHttpUrl(name: String): String? = nullableText(name)?.also { require(isHttpUrl(it)) { "Invalid $name" } }
private fun JSONArray?.requiredStrings(name: String): List<String> {
    require(this != null) { "Missing $name" }
    return (0 until length()).map { getString(it).trim().also { value -> require(value.isNotEmpty()) { "Blank $name value" } } }
}
private fun isHttpUrl(value: String) = value.matches(Regex("https?://[^\\s]+"))
private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
    .joinToString("") { "%02x".format(it) }
private val STATION_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val LEGACY_ID = Regex("^[a-z][a-z0-9-]{1,31}:[A-Za-z0-9][A-Za-z0-9._:-]{0,190}$")
private val CODEC = Regex("^[a-z0-9][a-z0-9.+-]*$")
