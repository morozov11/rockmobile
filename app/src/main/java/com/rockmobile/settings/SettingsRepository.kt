package com.rockmobile.settings

import android.content.Context

/** Central location for connection settings; UI for editing them is deliberately deferred. */
class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("rockmobile_settings", Context.MODE_PRIVATE)
    fun rockserverUrl(): String = preferences.getString(URL_KEY, DEFAULT_EMULATOR_URL)!!
    fun bearerToken(): String = preferences.getString(TOKEN_KEY, "")!!
    fun updateRockserver(url: String, bearerToken: String) {
        preferences.edit().putString(URL_KEY, url.trim()).putString(TOKEN_KEY, bearerToken.trim()).apply()
    }
    companion object {
        /** Android emulator alias for the host where RockServer defaults to port 3000. */
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:3000"
        private const val URL_KEY = "rockserver_url"
        private const val TOKEN_KEY = "rockserver_token"
    }
}
