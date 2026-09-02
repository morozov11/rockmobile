package com.rockmobile.data.stations

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.ApiError
import com.rockmobile.data.dto.parseRockserverStations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class StationSourcesTest {
    @Test fun bundledV1_parsesPinnedSchemaWithExplicitStableIds() {
        val bytes = File("src/main/assets/stations.v1.json").readBytes()
        val stations = parseSharedCatalog(bytes, "2026.08.2", "3fa20dca94fc059bd433a47b9fba9bb6d5e5e1aa2957a5ffb58b2a7b20b1d74d").map { it.station }
        assertEquals(41, stations.size)
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
        assertTrue(stations.all { it.name.isNotBlank() && it.streamUrl.matches(Regex("https?://.+")) })
    }

    @Test fun bundledV1_usesTheSameOrderAsRockcast() {
        val bytes = File("src/main/assets/stations.v1.json").readBytes()
        val stations = parseSharedCatalog(bytes, "2026.08.2", "3fa20dca94fc059bd433a47b9fba9bb6d5e5e1aa2957a5ffb58b2a7b20b1d74d").map { it.station }
        assertEquals(
            listOf(
                "181.FM — Hair Band", "181.FM — Hard Rock", "Metal Rock Radio", "Punk Rock Demonstration",
                "Rock Antenne", "Rock Antenne — Heavy Metal", "SomaFM — Metal Detector", "1.FM — High Voltage",
                "GotRadio — Hard Rock", "GotRadio — Metal Frontier", "GotRadio — Metal Madness", "GotRadio — Punk Rock",
                "Radio Caprice — Heavy Metal", "Radio Caprice — Industrial", "Radio Caprice — Nu Metal", "181.FM — Classic Buzz",
                "181.FM — Power", "181.FM — Rock 40", "181.FM — The Highway", "181.FM — The Rock!", "hit Radio FFH — Rock",
                "Radio Paradise — Rock Mix", "Radio Record — Rock", "Radio X UK", "Radio X UK (MP3)", "Rock Antenne — 80er Rock",
                "Rock Antenne — Alternative", "Rock Antenne — Classic Perlen", "Rock Antenne — Deutschrock", "Rock Antenne — Modern Rock",
                "Rock Antenne — Soft Rock", "Rock Antenne — Symphonic", "SomaFM — Boot Liquor", "SomaFM — Cover Song Army",
                "SomaFM — Indie Pop Rocks!", "SomaFM — Left Coast 70s", "SomaFM — PopTron", "SomaFM — Underground 80s",
                "1.FM — Classic Rock", "GotRadio — Alternative", "GotRadio — Classic Rock",
            ),
            orderLikeRockcast(stations).map { it.name },
        )
    }

    @Test fun bundledV1_rejectsChecksumAndVersionMismatch() {
        val bytes = catalog("""{"schemaVersion":1,"catalogVersion":"2026.08.2","stations":[$station],"tombstones":[]}""")
        assertFails { parseSharedCatalog(bytes, "2026.08.2", "0".repeat(64)) }
        assertFails { parseSharedCatalog(bytes, "2026.08.3", sha256(bytes)) }
    }

    @Test fun bundledV1_allowsMissingOptionalDataAndUsesPrimaryOfMultipleStreams() {
        val document = """{"schemaVersion":1,"catalogVersion":"2026.08.2","stations":[{"id":"stable-id","name":"Station","aliases":[],"legacyIds":[],"tags":[],"streams":[{"id":"backup","url":"https://example.test/backup","codec":null,"bitrateKbps":null,"primary":false},{"id":"main","url":"https://example.test/main","codec":"mp3","bitrateKbps":128,"primary":true}]}],"tombstones":[]}"""
        val bytes = catalog(document)
        val station = parseSharedCatalog(bytes, "2026.08.2", sha256(bytes)).single().station
        assertEquals("stable-id", station.id)
        assertEquals("https://example.test/main", station.streamUrl)
        assertEquals(2, station.streams.size)
        assertEquals(null, station.language)
    }

    @Test fun bundledV1_rejectsDuplicateIdsAndAmbiguousPrimary() {
        val duplicate = """{"schemaVersion":1,"catalogVersion":"2026.08.2","stations":[$station,$station],"tombstones":[]}"""
        val primary = station.replace("\"primary\":true", "\"primary\":true},{\"id\":\"other\",\"url\":\"https://example.test/other\",\"codec\":null,\"bitrateKbps\":null,\"primary\":true")
        assertFails { parseSharedCatalog(catalog(duplicate), "2026.08.2", sha256(catalog(duplicate))) }
        val ambiguous = """{"schemaVersion":1,"catalogVersion":"2026.08.2","stations":[$primary],"tombstones":[]}"""
        assertFails { parseSharedCatalog(catalog(ambiguous), "2026.08.2", sha256(catalog(ambiguous))) }
    }

    @Test fun remote_validSuccess_mapsServerDto() = runTest {
        val source = RockserverStationSource(RockserverApi(FakeTransport(200, validJson)), { "http://server" }, { "token" })
        assertEquals("station-rock-001", source.search("rock")?.single()?.id)
    }

    @Test fun remote_catalogue_followsServerCursorUntilTheLastPage() = runTest {
        val transport = PagingTransport()
        val source = RockserverStationSource(RockserverApi(transport), { "https://server.test" }, { "token" })
        assertEquals(listOf("first", "second"), source.loadCatalogue()?.map { it.id })
        assertEquals(
            listOf("https://server.test/api/v1/catalog/stations?limit=50", "https://server.test/api/v1/catalog/stations?limit=50&cursor=first"),
            transport.urls,
        )
    }

    @Test fun remote_search_usesPublicLimitCap() {
        val transport = CapturingTransport(200, validJson)
        RockserverApi(transport).search("https://alex.vault57.ru", "", "rock")
        assertEquals("https://alex.vault57.ru/api/v1/search", transport.url)
        assertEquals("", transport.bearerToken)
        assertEquals(20, org.json.JSONObject(transport.body).getInt("limit"))
    }

    @Test fun remote_malformedAndEmptyResponses_areRejected() = runTest {
        val malformed = RockserverStationSource(RockserverApi(FakeTransport(200, "{}")), { "http://server" }, { "token" })
        val empty = RockserverStationSource(RockserverApi(FakeTransport(200, "{\"stations\":[]}")), { "http://server" }, { "token" })
        runCatching { malformed.search("rock") }.onSuccess { throw AssertionError("malformed response accepted") }
        assertTrue(empty.search("rock")?.isEmpty() == true)
    }

    @Test fun remote_httpAndNetworkFailures_areExposed() {
        val http = RockserverApi(FakeTransport(503, "{}"))
        runCatching { http.search("http://server", "token", "rock") }.onSuccess { throw AssertionError("HTTP error accepted") }
            .onFailure { assertTrue(it is ApiError) }
        val offline = RockserverApi(object : HttpTransport { override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse = throw IOException("offline") })
        runCatching { offline.search("http://server", "token", "rock") }.onSuccess { throw AssertionError("network error accepted") }
    }

    private class FakeTransport(private val code: Int, private val body: String) : HttpTransport {
        override fun post(url: String, bearerToken: String, jsonBody: String) = HttpResponse(code, body)
    }
    private class PagingTransport : HttpTransport {
        val urls = mutableListOf<String>()
        override fun get(url: String, bearerToken: String): HttpResponse {
            urls += url
            return if (url.contains("cursor=first")) {
                HttpResponse(200, "{\"stations\":[{\"id\":\"second\",\"name\":\"Second\",\"stream_url\":\"https://example.test/second\",\"tags\":[\"jazz\"]}],\"next_cursor\":null}")
            } else {
                HttpResponse(200, "{\"stations\":[{\"id\":\"first\",\"name\":\"First\",\"stream_url\":\"https://example.test/first\",\"tags\":[\"rock\"]}],\"next_cursor\":\"first\"}")
            }
        }
        override fun post(url: String, bearerToken: String, jsonBody: String) = HttpResponse(200, validJson)
    }
    private class CapturingTransport(private val code: Int, private val responseBody: String) : HttpTransport {
        lateinit var url: String
        lateinit var bearerToken: String
        lateinit var body: String
        override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
            this.url = url
            this.bearerToken = bearerToken
            this.body = jsonBody
            return HttpResponse(code, responseBody)
        }
    }
    private fun catalog(value: String) = value.toByteArray()
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun assertFails(block: () -> Unit) { runCatching(block).onSuccess { throw AssertionError("invalid catalog accepted") } }
    private companion object {
        const val station = """{"id":"stable-id","name":"Station","aliases":[],"legacyIds":[],"tags":[],"streams":[{"id":"main","url":"https://example.test/main","codec":null,"bitrateKbps":null,"primary":true}]}"""
        const val validJson = "{\"stations\":[{\"id\":\"station-rock-001\",\"name\":\"Rock\",\"stream_url\":\"https://example.test/rock\",\"tags\":[\"rock\"],\"country_code\":\"US\",\"codec\":\"MP3\",\"bitrate_kbps\":128}]}"
    }
}
