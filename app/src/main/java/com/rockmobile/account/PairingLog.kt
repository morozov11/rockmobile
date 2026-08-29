package com.rockmobile.account

import android.util.Log
import com.rockmobile.data.api.ApiError

/** Safe pairing diagnostics for adb logcat; never logs tokens or secrets. */
internal object PairingLog {
    private const val TAG = "RockMobilePairing"

    fun restored(requestId: String) = runCatching { Log.i(TAG, "restored pending pairing requestId=$requestId") }

    fun pollRetry(error: Throwable) = logW("pairing poll will retry: ${safeSummary(error)}")

    fun pollFailed(error: Throwable) = logE("pairing poll failed: ${safeSummary(error)}")

    fun returnWithoutPending() = logW("browser return App Link received but no pending pairing in memory")

    private fun logW(message: String) = runCatching { Log.w(TAG, message) }

    private fun logE(message: String) = runCatching { Log.e(TAG, message) }

    private fun safeSummary(error: Throwable): String = when (error) {
        is ApiError -> "ApiError status=${error.statusCode} code=${error.code} requestId=${error.requestId}"
        else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
}
