package com.rockmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyStationIdMigrationTest {
    @Test fun approvedLegacyIds_migrateOnceAndPreserveUnknownValues() {
        val mappings = mapOf("rockmobile:rockcast-old" to "canonical-id")
        val once = migrateLegacyStationIds(setOf("rockcast-old", "unmatched"), mappings)
        assertEquals(setOf("canonical-id", "unmatched"), once)
        assertEquals(once, migrateLegacyStationIds(once, mappings))
    }
}
