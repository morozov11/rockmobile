package com.rockmobile.account

import org.json.JSONObject
import java.time.Instant

private const val MAX_DEVICE_NAME_BYTES = 128

internal fun defaultDeviceDisplayName(model: String): String =
    model.trim().ifBlank { "Android device" }

internal fun presentDeviceDisplayName(deviceType: String, value: String): String {
    val product = if (deviceType.contains("mobile", ignoreCase = true)) "RockMobile" else "RockCast"
    val rawName = value.trim().removePrefix("RockMobile — ").removePrefix("RockCast — ")
    return "$product — $rawName"
}

internal fun validateDeviceDisplayName(value: String): String? {
    val name = value.trim()
    return when {
        name.isEmpty() -> "Введите имя телефона."
        name.toByteArray(Charsets.UTF_8).size > MAX_DEVICE_NAME_BYTES ->
            "Имя телефона должно быть не длиннее 128 байт."
        else -> null
    }
}

/** Pairing proofs are deliberately memory-only. Do not make this Parcelable or save it in state. */
class PairingRequest(
    val requestId: String,
    private val desktopToken: String,
    private val approvalSecret: String,
    val shortCode: String,
    val verificationPhrase: String,
    val deviceDisplayName: String = "Android device",
    val deviceType: String = "rockmobile_android",
    val expiresAt: String = "",
    val status: String = "pending",
) {
    fun browserLink(baseUrl: String): String = "${baseUrl.trimEnd('/')}/?code=$shortCode&secret=$approvalSecret"
    internal fun completionBody(): JSONObject = JSONObject().put("desktop_token", desktopToken)
    internal fun expiresAtMs(): Long? = expiresAt.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }
}

class NativeCredentials(val accessToken: String, val refreshToken: String) {
    init { require(accessToken.length >= 16 && refreshToken.length >= 16) }
}

data class AccountProfile(
    val userId: String,
    val sessionId: String,
    val deviceId: String,
    val accountDisplayName: String = "Rock account",
    val deviceDisplayName: String = "Android device",
    val deviceType: String = "rockmobile_android",
    val createdAt: String? = null,
    val deviceCreatedAt: String? = null,
    val lastSeenAt: String? = null,
)

data class AccountDevice(
    val deviceId: String,
    val userId: String,
    val deviceDisplayName: String,
    val deviceType: String,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
)

sealed interface AccountUiState {
    data object Disconnected : AccountUiState
    data object Starting : AccountUiState
    data class Pairing(val request: PairingRequest) : AccountUiState
    data class Connected(
        val profile: AccountProfile,
        val devices: List<AccountDevice>,
        val message: String? = null,
        val devicesAvailable: Boolean = true,
    ) : AccountUiState
    data class Error(val message: String, val canRetryConnection: Boolean = true) : AccountUiState
}
