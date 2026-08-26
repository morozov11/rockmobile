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
