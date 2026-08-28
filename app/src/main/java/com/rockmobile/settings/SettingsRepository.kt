package com.rockmobile.settings

import android.content.Context
import android.content.SharedPreferences

/** Central location for the public server URL; account credentials never live here. */
class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("rockmobile_settings", Context.MODE_PRIVATE)

    init {
        scrubLegacyRockserverDefaults(preferences)
    }

    fun rockserverUrl(): String = preferences.getString(URL_KEY, PRODUCTION_BASE_URL)!!.ifBlank { PRODUCTION_BASE_URL }
    /** Anonymous catalogue/voice routes do not need a bearer token. Native account tokens use KeystoreCredentialStore. */
    fun bearerToken(): String = ""
    fun updateRockserver(url: String, @Suppress("UNUSED_PARAMETER") bearerToken: String) {
        preferences.edit().putString(URL_KEY, url.trim()).remove(TOKEN_KEY).apply()
    }

    companion object {
        /** Public RockServer used by official Rockmobile releases. */
        const val PRODUCTION_BASE_URL = "https://alex.vault57.ru"
        /** Android emulator alias retained only for recognition of legacy installs. */
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:3000"
        /** Former laptop LAN default retained only for recognition of legacy installs. */
        const val DEFAULT_LAPTOP_URL = "http://192.168.31.133:3000"
        /** Former shared bootstrap credential; retained only to scrub legacy installs. */
        const val DEFAULT_BEARER_TOKEN = "rockserver-dev-bootstrap-7f4b9a2c1e6d8a40"
        internal const val URL_KEY = "rockserver_url"
        internal const val TOKEN_KEY = "rockserver_token"
    }
}

/** Replaces former LAN/emulator defaults and the shared bootstrap token with the official public client config. */
internal fun scrubLegacyRockserverDefaults(preferences: SharedPreferences) {
    val editor = preferences.edit()
    var changed = false
    if (preferences.contains(SettingsRepository.URL_KEY)) {
        val replacement = migratedStoredRockserverUrl(preferences.getString(SettingsRepository.URL_KEY, null).orEmpty())
        if (replacement != null) {
            editor.putString(SettingsRepository.URL_KEY, replacement)
            changed = true
        }
    }
    if (preferences.contains(SettingsRepository.TOKEN_KEY)) {
        editor.remove(SettingsRepository.TOKEN_KEY)
        changed = true
    }
    if (changed) editor.apply()
}

/** Returns the production URL when [stored] is a known legacy default; otherwise null (no change). */
internal fun migratedStoredRockserverUrl(stored: String): String? {
    val url = stored.trim()
    return if (url.isEmpty() ||
        url == SettingsRepository.DEFAULT_EMULATOR_URL ||
        url == SettingsRepository.DEFAULT_LAPTOP_URL
    ) {
        SettingsRepository.PRODUCTION_BASE_URL
    } else {
        null
    }
}

/** Clears the shared bootstrap token; otherwise null (no change). */
internal fun migratedStoredBearerToken(stored: String): String? =
    if (stored.trim() == SettingsRepository.DEFAULT_BEARER_TOKEN) "" else null
