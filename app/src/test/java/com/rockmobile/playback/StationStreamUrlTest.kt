package com.rockmobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import com.rockmobile.data.api.ApiError
import kotlinx.coroutines.test.runTest
import java.io.IOException

class StationStreamUrlTest {
    @Test fun credentialsAreResolvedForEachConnectionAndStayOutOfTheUrl() = runTest {
        val base = "https://server.test"
        val url = stationStreamUrl(base, "station")
        var calls = 0
        repeat(2) {
            val headers = stationStreamHeaders(base, url, mapOf("Icy-MetaData" to "1")) {
                calls++
                "synthetic-$calls"
            }
            assertEquals("Bearer synthetic-$calls", headers["Authorization"])
            assertEquals("1", headers["Icy-MetaData"])
        }
        assertEquals(2, calls)
        assertFalse(url.contains("synthetic"))
        val rejected = runCatching {
            stationStreamHeaders(base, "https://upstream.test/live.mp3", emptyMap()) {
                calls++
                "must-not-be-read"
            }
        }
        assertTrue(rejected.exceptionOrNull() is IOException)
        assertEquals(2, calls)
    }

    @Test fun missingSessionReportsSignInAndRenewalFailureStopsPlayback() = runTest {
        val base = "https://server.test"
        val url = stationStreamUrl(base, "station")
        val missing = runCatching { stationStreamHeaders(base, url, emptyMap()) { null } }.exceptionOrNull()
        assertTrue(missing is DataSourceException)
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, (missing as DataSourceException).reason)
        assertEquals("Sign in to listen through RockServer", playbackErrorMessage(missing.reason))
        val revoked = runCatching {
            stationStreamHeaders(base, url, emptyMap()) { throw ApiError(401, "device_credential_invalid") }
        }.exceptionOrNull()
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, (revoked as DataSourceException).reason)
        val failure = IOException("offline")
        val renewal = runCatching { stationStreamHeaders(base, url, emptyMap()) { throw failure } }.exceptionOrNull()
        assertTrue(renewal is IOException)
        assertTrue(renewal?.cause === failure)
    }

    @Test fun stationIdIsOneEncodedPathSegment() {
        val url = stationStreamUrl("https://server.test/", "radio/рок ?#")
        assertEquals("https://server.test/api/v1/stations/radio%2F%D1%80%D0%BE%D0%BA%20%3F%23/stream", url)
        assertTrue(isStationStreamUrl("https://server.test", url))
    }

    @Test fun credentialsAreLimitedToTheConfiguredRelay() {
        val base = "https://server.test"
        for (url in listOf(
            "https://server.test.evil/api/v1/stations/id/stream",
            "http://server.test/api/v1/stations/id/stream",
            "https://server.test/api/v1/stations/id/stream?token=x",
            "https://server.test/api/v1/stations/id/stream#fragment",
            "https://server.test/api/v1/stations/a/b/stream",
            "https://server.test/api/v1/stations//stream",
            "https://upstream.test/live.mp3",
        )) assertFalse(url, isStationStreamUrl(base, url))
    }

    @Test fun rejectsInvalidStationIds() {
        for (id in listOf("", ".", "..", "a\nb", "я".repeat(65))) {
            assertTrue(runCatching { stationStreamUrl("https://server.test", id) }.isFailure)
        }
    }
}
