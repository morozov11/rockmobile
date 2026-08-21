package com.rockmobile.settings

import android.content.Context

/** Persists only voice-result stations whose stream could not be opened. */
class UnavailableVoiceStationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun unavailableStationIds(): Set<String> = preferences.getStringSet(UNAVAILABLE_IDS_KEY, emptySet()).orEmpty().toSet()

    fun markUnavailable(stationId: String) {
        if (stationId.isBlank()) return
        val ids = unavailableStationIds().toMutableSet()
        ids += stationId
        preferences.edit().putStringSet(UNAVAILABLE_IDS_KEY, ids.take(MAX_IDS).toSet()).apply()
    }

    fun markAvailable(stationId: String) {
        val ids = unavailableStationIds().toMutableSet()
        if (ids.remove(stationId)) preferences.edit().putStringSet(UNAVAILABLE_IDS_KEY, ids).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "rockmobile_voice_availability"
        const val UNAVAILABLE_IDS_KEY = "unavailable_voice_station_ids"
        const val MAX_IDS = 100
    }
}
