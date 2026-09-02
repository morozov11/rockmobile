package com.rockmobile.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccessTokenPolicyTest {
    @Test fun refreshOnlyWhenUnknownOrNearExpiry() {
        val now = 1_700_000_000_000L
        val fresh = now + 10 * 60 * 1000
        assertFalse(accessTokenNeedsRefresh(fresh, now))
        assertTrue(accessTokenNeedsRefresh(0L, now))
        assertTrue(accessTokenNeedsRefresh(now + ACCESS_TOKEN_REFRESH_LEAD_MS, now))
        assertFalse(accessTokenNeedsRefresh(now + ACCESS_TOKEN_REFRESH_LEAD_MS + 1, now))
    }

    @Test fun parsesServerExpiryTimestamp() {
        val expiresAt = Instant.parse("2030-01-01T12:00:00Z").toEpochMilli()
        val body = org.json.JSONObject("""{"access_expires_at":"2030-01-01T12:00:00Z"}""")
        assertEquals(expiresAt, parseAccessExpiresAtMs(body))
    }
}
