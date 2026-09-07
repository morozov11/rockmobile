package com.rockmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RockserverSettingsMigrationTest {
    @Test fun legacyLanAndEmulatorUrls_becomeProduction() {
        assertEquals(
            SettingsRepository.PRODUCTION_BASE_URL,
            migratedStoredRockserverUrl(SettingsRepository.DEFAULT_LAPTOP_URL),
        )
        assertEquals(
            SettingsRepository.PRODUCTION_BASE_URL,
            migratedStoredRockserverUrl(SettingsRepository.DEFAULT_EMULATOR_URL),
        )
        assertEquals(SettingsRepository.PRODUCTION_BASE_URL, migratedStoredRockserverUrl("  "))
    }

    @Test fun storedCustomUrl_becomesProduction() {
        assertEquals(SettingsRepository.PRODUCTION_BASE_URL, migratedStoredRockserverUrl("https://dev.example.test:8443"))
        assertEquals(SettingsRepository.PRODUCTION_BASE_URL, migratedStoredRockserverUrl(SettingsRepository.PRODUCTION_BASE_URL))
    }

    @Test fun bootstrapToken_isCleared() {
        assertEquals("", migratedStoredBearerToken(SettingsRepository.DEFAULT_BEARER_TOKEN))
        assertNull(migratedStoredBearerToken(""))
        assertNull(migratedStoredBearerToken("custom-token"))
    }

    @Test fun debugEndpoint_isHttpsOnlyAndNeverChangesRelease() {
        assertEquals("https://control.test", resolvedRockserverUrl(true, "https://control.test/"))
        assertEquals(SettingsRepository.PRODUCTION_BASE_URL, resolvedRockserverUrl(true, "http://10.0.2.2:3000"))
        assertEquals(SettingsRepository.PRODUCTION_BASE_URL, resolvedRockserverUrl(false, "https://control.test"))
    }
}
