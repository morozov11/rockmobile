package com.rockmobile.data.stations

import android.content.res.AssetManager
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.dto.parseRockserverStations
import com.rockmobile.domain.model.Station
import java.security.MessageDigest
import android.util.Log

interface RemoteStationSource { suspend fun load(): List<Station> }
interface LocalStationSource { suspend fun load(): List<Station> }

class RockserverStationSource(
    private val api: RockserverApi,
    private val baseUrl: () -> String,
    private val bearerToken: () -> String,
) : RemoteStationSource {
    override suspend fun load(): List<Station> = try {
        parseRockserverStations(api.search(baseUrl(), bearerToken()))
    } catch (error: Throwable) {
        Log.e("RockserverApi", "Catalogue parsing/request failed", error)
        throw error
    }
}

/** Reads the unmodified RockCast text format, keeping the fallback traceable and editable. */
class RockcastAssetStationSource(private val assets: AssetManager) : LocalStationSource {
    override suspend fun load(): List<Station> = assets.open("stations.txt").bufferedReader().use(::parseRockcastStations)
}

internal fun parseRockcastStations(reader: java.io.Reader): List<Station> = reader.readText().lineSequence()
    .map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.mapNotNull { line ->
        val fields = line.split('|').map(String::trim)
        if (fields.size < 2 || fields[0].isBlank() || !fields[1].matches(Regex("https?://.+"))) null else Station(
            id = "rockcast-" + sha256(fields[1].trimEnd('/').lowercase()).take(16), name = fields[0], streamUrl = fields[1],
            tags = fields.getOrNull(2).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            bitrateKbps = fields.getOrNull(3)?.toIntOrNull(), codec = fields.getOrNull(4)?.ifBlank { null },
            country = fields.getOrNull(5)?.ifBlank { null },
        )
    }.toList()

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
