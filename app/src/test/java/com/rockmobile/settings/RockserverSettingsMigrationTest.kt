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

    @Test fun customUrl_isPreserved() {
        assertNull(migratedStoredRockserverUrl("https://dev.example.test:8443"))
        assertNull(migratedStoredRockserverUrl(SettingsRepository.PRODUCTION_BASE_URL))
    }

    @Test fun bootstrapToken_isCleared() {
        assertEquals("", migratedStoredBearerToken(SettingsRepository.DEFAULT_BEARER_TOKEN))
        assertNull(migratedStoredBearerToken(""))
        assertNull(migratedStoredBearerToken("custom-token"))
    }
}
