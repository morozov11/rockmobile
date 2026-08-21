package com.rockmobile.settings

/** Pure migration step so persisted-state upgrades can be tested without Android storage. */
internal fun migrateLegacyStationIds(currentIds: Set<String>, approvedMappings: Map<String, String>): Set<String> =
    currentIds.map { approvedMappings["rockmobile:$it"] ?: it }.toSet()
