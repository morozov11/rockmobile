package com.rockmobile.data.stations

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.RockserverHttpException
import com.rockmobile.data.dto.parseRockserverStations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class StationSourcesTest {
    @Test fun bundledFormat_parsesRequiredFieldsWithUniqueStableIdsAndHttpUrls() {
        val stations = File("src/main/assets/stations.txt").reader().use(::parseRockcastStations)
        assertEquals(41, stations.size)
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
        assertTrue(stations.all { it.name.isNotBlank() && it.streamUrl.matches(Regex("https?://.+")) })
    }

    @Test fun remote_validSuccess_mapsServerDto() = runTest {
        val source = RockserverStationSource(RockserverApi(FakeTransport(200, validJson)), { "http://server" }, { "token" })
        assertEquals("station-rock-001", source.load().single().id)
    }

    @Test fun remote_malformedAndEmptyResponses_areRejected() = runTest {
        val malformed = RockserverStationSource(RockserverApi(FakeTransport(200, "{}")), { "http://server" }, { "token" })
        val empty = RockserverStationSource(RockserverApi(FakeTransport(200, "{\"stations\":[]}")), { "http://server" }, { "token" })
        runCatching { malformed.load() }.onSuccess { throw AssertionError("malformed response accepted") }
        assertTrue(empty.load().isEmpty())
    }

    @Test fun remote_httpAndNetworkFailures_areExposed() {
        val http = RockserverApi(FakeTransport(503, "{}"))
        runCatching { http.search("http://server", "token") }.onSuccess { throw AssertionError("HTTP error accepted") }
            .onFailure { assertTrue(it is RockserverHttpException) }
        val offline = RockserverApi(object : HttpTransport { override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse = throw IOException("offline") })
        runCatching { offline.search("http://server", "token") }.onSuccess { throw AssertionError("network error accepted") }
    }

    private class FakeTransport(private val code: Int, private val body: String) : HttpTransport {
        override fun post(url: String, bearerToken: String, jsonBody: String) = HttpResponse(code, body)
    }
    private companion object { const val validJson = "{\"stations\":[{\"id\":\"station-rock-001\",\"name\":\"Rock\",\"stream_url\":\"https://example.test/rock\",\"tags\":[\"rock\"],\"country_code\":\"US\",\"codec\":\"MP3\",\"bitrate_kbps\":128}]}" }
}
