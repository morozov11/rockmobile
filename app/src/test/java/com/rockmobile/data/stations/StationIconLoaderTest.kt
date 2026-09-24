package com.rockmobile.data.stations

import com.rockmobile.domain.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIconLoaderTest {
    private fun station(
        id: String = "station/with\\unsafe",
        homepageUrl: String? = null,
        faviconUrl: String? = null,
    ) = Station(
        id = id,
        name = "Test",
        streamUrl = "https://stream.example.test/live",
        homepageUrl = homepageUrl,
        faviconUrl = faviconUrl,
    )

    @Test fun serverRelativeFaviconPath_resolvesAgainstRockserverBase() {
        val station = station(faviconUrl = "/api/v1/stations/station-1/icon")
        assertEquals(
            "https://rockplatform.win/api/v1/stations/station-1/icon",
            StationIconLoader.sourceUrl(station, "https://rockplatform.win"),
        )
        assertEquals(
            "https://rockplatform.win/api/v1/stations/station-1/icon",
            StationIconLoader.sourceUrl(station, "https://rockplatform.win/"),
        )
    }

    @Test fun serverRelativeFaviconPath_withoutBase_isRejectedWithoutHomepageScraping() {
        val station = station(homepageUrl = "https://radio.example.test/home", faviconUrl = "/api/v1/stations/station-1/icon")
        assertNull(StationIconLoader.sourceUrl(station))
        assertNull(StationIconLoader.sourceUrl(station, "not-a-url"))
    }

    @Test fun protocolRelativeAndNonPathFaviconSources_areRejected() {
        assertNull(StationIconLoader.sourceUrl(station(faviconUrl = "//evil.example.test/icon.png"), "https://rockplatform.win"))
        assertNull(StationIconLoader.sourceUrl(station(faviconUrl = "api/v1/icon"), "https://rockplatform.win"))
    }

    @Test fun absoluteFavicon_winsOverServerBase() {
        assertEquals(
            "https://cdn.example.test/logo.png",
            StationIconLoader.sourceUrl(
                station(faviconUrl = "https://cdn.example.test/logo.png"),
                "https://rockplatform.win",
            ),
        )
    }

    @Test fun explicitFavicon_winsOverHomepage() {
        assertEquals(
            "https://cdn.example.test/logo.png?size=64",
            StationIconLoader.sourceUrl(
                station(
                    homepageUrl = "https://radio.example.test/home",
                    faviconUrl = "https://cdn.example.test/logo.png?size=64",
                ),
            ),
        )
    }

    @Test fun homepageFallback_isConventionalPathWithoutScraping() {
        assertEquals(
            "https://radio.example.test/favicon.ico",
            StationIconLoader.sourceUrl(station(homepageUrl = "https://radio.example.test/home?utm=ignored")),
        )
    }

    @Test fun unsafeUrls_areRejected() {
        assertNull(StationIconLoader.sourceUrl(station(faviconUrl = "file:///tmp/logo.png")))
        assertNull(StationIconLoader.sourceUrl(station(faviconUrl = "https://user:secret@example.test/logo.png")))
    }

    @Test fun cacheStem_isPathSafe() {
        val stem = StationIconLoader.cacheStem(station())
        assertTrue(stem.startsWith("v1-"))
        assertTrue(!stem.contains("..") && !stem.contains("/") && !stem.contains("\\"))
    }
}
