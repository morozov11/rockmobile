package com.rockmobile.account

import android.util.Log
import com.rockmobile.data.api.ApiError

/** Safe account-session diagnostics for adb logcat; never logs tokens. */
internal object SessionLog {
    private const val TAG = "RockMobileSession"

    fun probeStarted(hasCredentials: Boolean) =
        runCatching { Log.i(TAG, "probe stored session credentialsPresent=$hasCredentials") }

    fun probeConnected(deviceId: String) =
        runCatching { Log.i(TAG, "account session connected deviceId=$deviceId") }

    fun probeOffline(message: String) = runCatching { Log.w(TAG, message) }

    fun refreshFailed(error: Throwable) = runCatching { Log.w(TAG, "refresh failed: ${safeSummary(error)}") }

    fun persistFailed(error: Throwable) = runCatching { Log.e(TAG, "credential persist failed: ${safeSummary(error)}") }

    fun credentialsCleared(reason: String) = runCatching { Log.w(TAG, "local credentials cleared: $reason") }

    private fun safeSummary(error: Throwable): String = when (error) {
        is ApiError -> "ApiError status=${error.statusCode} code=${error.code} requestId=${error.requestId}"
        else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
}
