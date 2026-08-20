package com.rockmobile.settings

import android.content.Context

/** Central location for connection settings; UI for editing them is deliberately deferred. */
class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("rockmobile_settings", Context.MODE_PRIVATE)
    fun rockserverUrl(): String = preferences.getString(URL_KEY, DEFAULT_LAPTOP_URL)!!
    fun bearerToken(): String = preferences.getString(TOKEN_KEY, DEFAULT_BEARER_TOKEN)!!
    fun updateRockserver(url: String, bearerToken: String) {
        preferences.edit().putString(URL_KEY, url.trim()).putString(TOKEN_KEY, bearerToken.trim()).apply()
    }
    companion object {
        /** Android emulator alias for the host where RockServer defaults to port 3000. */
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:3000"
        /** Current laptop LAN address where the local RockServer is listening. */
        const val DEFAULT_LAPTOP_URL = "http://192.168.31.133:3000"
        /** Temporary RockServer bootstrap credential used until user accounts are available. */
        const val DEFAULT_BEARER_TOKEN = "rockserver-dev-bootstrap-7f4b9a2c1e6d8a40"
        private const val URL_KEY = "rockserver_url"
        private const val TOKEN_KEY = "rockserver_token"
    }
}
