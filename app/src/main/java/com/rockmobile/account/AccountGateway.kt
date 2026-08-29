package com.rockmobile.account

import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.ApiError
import com.rockmobile.BuildConfig
import org.json.JSONObject

interface AccountGateway {
    fun createPairing(deviceName: String): PairingRequest
    /** 202 means browser approval has not happened yet; the request body contains only desktop_token. */
    fun completePairing(pairing: PairingRequest): Pair<AccountProfile, NativeCredentials>
    fun refresh(refreshToken: String): NativeCredentials
    fun profile(accessToken: String): AccountProfile
    fun devices(accessToken: String): List<AccountDevice>
    fun revokeDevice(accessToken: String, deviceId: String)
    fun logout(accessToken: String)
}

class RockserverAccountGateway(private val api: RockserverApi, private val baseUrl: () -> String) : AccountGateway {
    override fun createPairing(deviceName: String): PairingRequest {
        val response = api.post(baseUrl(), "/v1/pairing-requests", body = JSONObject()
            .put("device_display_name", deviceName.trim())
            .put("device_type", "rockmobile_android")
            .put("app_version", BuildConfig.VERSION_NAME))
        requireSuccess(response)
        val body = JSONObject(response.body)
        return PairingRequest(
            requestId = body.getString("pairing_request_id"),
            desktopToken = body.getString("desktop_token"),
            approvalSecret = body.getString("approval_secret"),
            shortCode = body.getString("short_code"),
            verificationPhrase = body.getString("verification_phrase"),
            deviceDisplayName = body.getString("device_display_name"),
            deviceType = body.getString("device_type"),
            expiresAt = body.getString("expires_at"),
            status = body.getString("status"),
        )
    }

    override fun completePairing(pairing: PairingRequest): Pair<AccountProfile, NativeCredentials> {
        val response = api.post(baseUrl(), "/v1/pairing-requests/${pairing.requestId}/complete", body = pairing.completionBody())
        if (response.code == 202) throw ApiError.from(response)
        requireSuccess(response)
        return completion(JSONObject(response.body))
    }

    override fun refresh(refreshToken: String): NativeCredentials {
        val response = api.post(baseUrl(), "/v1/auth/refresh", body = JSONObject().put("refresh_token", refreshToken))
        requireSuccess(response)
        return credentials(JSONObject(response.body))
    }

    override fun profile(accessToken: String): AccountProfile {
        val response = api.get(baseUrl(), "/v1/account/profile", accessToken)
        requireSuccess(response)
        return profile(JSONObject(response.body))
    }

    override fun devices(accessToken: String): List<AccountDevice> {
        val response = api.get(baseUrl(), "/v1/devices", accessToken)
        requireSuccess(response)
        val devices = JSONObject(response.body).getJSONArray("devices")
        return List(devices.length()) { index -> device(devices.getJSONObject(index)) }
    }

    override fun revokeDevice(accessToken: String, deviceId: String) {
        requireSuccess(api.delete(baseUrl(), "/v1/devices/$deviceId", accessToken))
    }

    override fun logout(accessToken: String) {
        requireSuccess(api.post(baseUrl(), "/v1/auth/logout", accessToken), allowUnauthorized = true)
    }

    private fun requireSuccess(response: com.rockmobile.data.api.HttpResponse, allowUnauthorized: Boolean = false) {
        if (response.code !in 200..299 && !(allowUnauthorized && response.code == 401)) throw ApiError.from(response)
    }

    private fun completion(body: JSONObject) = profile(body) to credentials(body)

    private fun profile(body: JSONObject) = AccountProfile(
        userId = body.getString("user_id"),
        sessionId = body.getString("session_id"),
        deviceId = body.getString("device_id"),
        accountDisplayName = body.getString("account_display_name"),
        deviceDisplayName = body.getString("device_display_name"),
        deviceType = body.getString("device_type"),
        createdAt = body.optString("created_at").takeIf(String::isNotBlank),
        deviceCreatedAt = body.optString("device_created_at").takeIf(String::isNotBlank),
        lastSeenAt = body.optString("last_seen_at").takeIf(String::isNotBlank),
    )

    private fun credentials(body: JSONObject) = NativeCredentials(body.getString("access_token"), body.getString("refresh_token"))

    private fun device(body: JSONObject) = AccountDevice(
        deviceId = body.getString("device_id"),
        userId = body.getString("user_id"),
        deviceDisplayName = body.getString("device_display_name"),
        deviceType = body.getString("device_type"),
        createdAt = body.optString("created_at").takeIf(String::isNotBlank),
        lastSeenAt = body.optString("last_seen_at").takeIf(String::isNotBlank),
    )
}
