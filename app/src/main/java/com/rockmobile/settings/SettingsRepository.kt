package com.rockmobile.settings

import android.content.Context
import android.content.SharedPreferences

/** Central location for the fixed public server URL; account credentials never live here. */
class SettingsRepository(context: Context) : com.rockmobile.devicecontrol.TargetSelectionStore {
    private val preferences = context.getSharedPreferences("rockmobile_settings", Context.MODE_PRIVATE)

    init {
        scrubLegacyRockserverDefaults(preferences)
    }

    fun rockserverUrl(): String = PRODUCTION_BASE_URL
    /** Anonymous catalogue/voice routes do not need a bearer token. Native account tokens use KeystoreCredentialStore. */
    fun bearerToken(): String = ""

    override fun load(userId: String, controllerDeviceId: String): String? =
        preferences.getString(targetKey(userId, controllerDeviceId), null)?.takeIf(String::isNotBlank)

    override fun save(userId: String, controllerDeviceId: String, targetId: String) {
        preferences.edit().putString(targetKey(userId, controllerDeviceId), targetId).apply()
    }

    override fun clear(userId: String, controllerDeviceId: String) {
        preferences.edit().remove(targetKey(userId, controllerDeviceId)).apply()
    }

    private fun targetKey(userId: String, controllerDeviceId: String) = "selected_control_target:$userId:$controllerDeviceId"
    fun updateRockserver(@Suppress("UNUSED_PARAMETER") url: String, @Suppress("UNUSED_PARAMETER") bearerToken: String) {
        preferences.edit().remove(URL_KEY).remove(TOKEN_KEY).apply()
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

/** Removes obsolete endpoint overrides and bootstrap credentials from official client installs. */
internal fun scrubLegacyRockserverDefaults(preferences: SharedPreferences) {
    val editor = preferences.edit()
    var changed = false
    if (preferences.contains(SettingsRepository.URL_KEY)) {
        editor.remove(SettingsRepository.URL_KEY)
        changed = true
    }
    if (preferences.contains(SettingsRepository.TOKEN_KEY)) {
        editor.remove(SettingsRepository.TOKEN_KEY)
        changed = true
    }
    if (changed) editor.apply()
}

/** Returns the production URL for every legacy setting; official builds have no mutable endpoint. */
internal fun migratedStoredRockserverUrl(stored: String): String? {
    return stored.takeIf { it.isNotEmpty() }?.let { SettingsRepository.PRODUCTION_BASE_URL }
}

/** Clears the shared bootstrap token; otherwise null (no change). */
internal fun migratedStoredBearerToken(stored: String): String? =
    if (stored.trim() == SettingsRepository.DEFAULT_BEARER_TOKEN) "" else null
