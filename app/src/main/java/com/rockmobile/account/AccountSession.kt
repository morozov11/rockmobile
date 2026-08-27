package com.rockmobile.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.RockserverHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val PAIRING_TIMEOUT_MS = 10 * 60 * 1000L
private const val PAIRING_POLL_MS = 2_000L

internal fun shouldContinuePairing(error: Throwable, nowMs: Long, deadlineMs: Long): Boolean =
    error is RockserverHttpException && error.statusCode == 401 && nowMs < deadlineMs

/** Pairing proofs are deliberately memory-only. Do not make this Parcelable or save it in state. */
class PairingRequest(
    val requestId: String,
    private val desktopToken: String,
    private val approvalSecret: String,
    val shortCode: String,
    val verificationPhrase: String,
) {
    fun browserLink(baseUrl: String): String = "${baseUrl.trimEnd('/')}/?code=$shortCode&secret=$approvalSecret"
    internal fun completionBody(): JSONObject = JSONObject().put("desktop_token", desktopToken)
}

class NativeCredentials(val accessToken: String, val refreshToken: String) {
    init { require(accessToken.length >= 16 && refreshToken.length >= 16) }
}

data class AccountProfile(val userId: String, val sessionId: String, val deviceId: String)
data class AccountDevice(val deviceId: String, val userId: String, val name: String, val platform: String)

interface AccountGateway {
    fun createPairing(deviceName: String): PairingRequest
    /** 401 means browser approval has not happened yet; the request body contains only desktop_token. */
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
            .put("device_name", deviceName).put("platform", "android").put("app_version", "0.1.0"))
        requireSuccess(response.code)
        val body = JSONObject(response.body)
        return PairingRequest(body.getString("pairing_request_id"), body.getString("desktop_token"), body.getString("approval_secret"), body.getString("short_code"), body.getString("verification_phrase"))
    }

    override fun completePairing(pairing: PairingRequest): Pair<AccountProfile, NativeCredentials> {
        val response = api.post(baseUrl(), "/v1/pairing-requests/${pairing.requestId}/complete", body = pairing.completionBody())
        requireSuccess(response.code)
        return completion(JSONObject(response.body))
    }

    override fun refresh(refreshToken: String): NativeCredentials {
        val response = api.post(baseUrl(), "/v1/auth/refresh", body = JSONObject().put("refresh_token", refreshToken))
        requireSuccess(response.code)
        return credentials(JSONObject(response.body))
    }

    override fun profile(accessToken: String): AccountProfile {
        val response = api.get(baseUrl(), "/v1/account/profile", accessToken); requireSuccess(response.code)
        return profile(JSONObject(response.body))
    }
    override fun devices(accessToken: String): List<AccountDevice> {
        val response = api.get(baseUrl(), "/v1/devices", accessToken); requireSuccess(response.code)
        val devices = JSONObject(response.body).getJSONArray("devices")
        return List(devices.length()) { index -> device(devices.getJSONObject(index)) }
    }
    override fun revokeDevice(accessToken: String, deviceId: String) {
        requireSuccess(api.delete(baseUrl(), "/v1/devices/$deviceId", accessToken).code)
    }
    override fun logout(accessToken: String) { requireSuccess(api.post(baseUrl(), "/v1/auth/logout", accessToken).code, allowUnauthorized = true) }

    private fun requireSuccess(code: Int, allowUnauthorized: Boolean = false) {
        if (code !in 200..299 && !(allowUnauthorized && code == 401)) throw RockserverHttpException(code)
    }
    private fun completion(body: JSONObject) = profile(body) to credentials(body)
    private fun profile(body: JSONObject) = AccountProfile(body.getString("user_id"), body.getString("session_id"), body.getString("device_id"))
    private fun credentials(body: JSONObject) = NativeCredentials(body.getString("access_token"), body.getString("refresh_token"))
    private fun device(body: JSONObject) = AccountDevice(body.getString("device_id"), body.getString("user_id"), body.getString("name"), body.getString("platform"))
}

interface CredentialStore {
    fun load(): NativeCredentials?
    fun save(credentials: NativeCredentials)
    fun clear()
}

/** Encrypted private file; the AES key is non-exportable and held in Android Keystore. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val file = File(context.noBackupFilesDir, "native_session.bin")
    override fun load(): NativeCredentials? = try {
        if (!file.exists()) null else decrypt(file.readBytes()).let { bytes ->
            JSONObject(bytes.toString(Charsets.UTF_8)).let { NativeCredentials(it.getString("access_token"), it.getString("refresh_token")) }
        }
    } catch (_: Exception) { clear(); null }
    override fun save(credentials: NativeCredentials) {
        val plain = JSONObject().put("access_token", credentials.accessToken).put("refresh_token", credentials.refreshToken).toString().toByteArray()
        file.parentFile?.mkdirs(); file.writeBytes(encrypt(plain))
    }
    override fun clear() { file.delete() }

    private fun key() = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()) as javax.crypto.SecretKey
    private fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(plain)
    }
    private fun decrypt(ciphertext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(ciphertext.size > 12); init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12))); doFinal(ciphertext, 12, ciphertext.size - 12)
    }
    private companion object { const val KEY_ALIAS = "rockmobile_native_session" }
}

sealed interface AccountUiState {
    data object Disconnected : AccountUiState
    data class Pairing(val request: PairingRequest) : AccountUiState
    data class Connected(val profile: AccountProfile, val devices: List<AccountDevice>, val message: String? = null) : AccountUiState
    data class Error(val message: String) : AccountUiState
}

class AccountViewModel(private val gateway: AccountGateway, private val store: CredentialStore) : ViewModel() {
    private val _state = MutableStateFlow<AccountUiState>(AccountUiState.Disconnected)
    val state: StateFlow<AccountUiState> = _state.asStateFlow()
    private var pairingJob: Job? = null

    init { refreshAccount() }

    fun connect(deviceName: String) {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            try {
                val request = withContext(Dispatchers.IO) { gateway.createPairing(deviceName) }
                _state.value = AccountUiState.Pairing(request)
                val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val result = withContext(Dispatchers.IO) { gateway.completePairing(request) }
                        withContext(Dispatchers.IO) { store.save(result.second) }
                        loadAccount(result.first)
                        return@launch
                    } catch (error: Exception) {
                        if (!shouldContinuePairing(error, System.currentTimeMillis(), deadline)) throw error
                    }
                    delay(PAIRING_POLL_MS)
                }
                _state.value = AccountUiState.Error("Pairing timed out. Radio is still available without an account.")
            } catch (_: CancellationException) { }
            catch (_: Exception) { _state.value = AccountUiState.Error("Could not connect to RockServer. Radio is still available offline.") }
        }
    }

    fun cancelPairing() { pairingJob?.cancel(); pairingJob = null; _state.value = AccountUiState.Disconnected }
    fun refreshAccount() = viewModelScope.launch {
        try {
            val credentials = withContext(Dispatchers.IO) { store.load() } ?: return@launch
            val fresh = withContext(Dispatchers.IO) { gateway.refresh(credentials.refreshToken) }
            withContext(Dispatchers.IO) { store.save(fresh) }
            loadAccount()
        } catch (_: Exception) { withContext(Dispatchers.IO) { store.clear() }; _state.value = AccountUiState.Disconnected }
    }
    fun revoke(deviceId: String) = viewModelScope.launch {
        try {
            authorized { gateway.revokeDevice(it, deviceId) }
            if ((state.value as? AccountUiState.Connected)?.profile?.deviceId == deviceId) {
                withContext(Dispatchers.IO) { store.clear() }
                _state.value = AccountUiState.Disconnected
            } else loadAccount()
        }
        catch (_: Exception) { _state.value = AccountUiState.Error("Could not revoke that device.") }
    }
    fun logout() = viewModelScope.launch {
        val credentials = withContext(Dispatchers.IO) { store.load() }
        try { if (credentials != null) withContext(Dispatchers.IO) { gateway.logout(credentials.accessToken) } } catch (_: Exception) { }
        withContext(Dispatchers.IO) { store.clear() }; _state.value = AccountUiState.Disconnected
    }
    private suspend fun loadAccount(profile: AccountProfile? = null) {
        val actualProfile = profile ?: authorized { gateway.profile(it) }
        val devices = authorized { gateway.devices(it) }
        _state.value = AccountUiState.Connected(actualProfile, devices)
    }
    private suspend fun <T> authorized(block: (String) -> T): T {
        val credentials = withContext(Dispatchers.IO) { store.load() } ?: throw IllegalStateException("No session")
        return try { withContext(Dispatchers.IO) { block(credentials.accessToken) } }
        catch (error: RockserverHttpException) {
            if (error.statusCode != 401) throw error
            val fresh = withContext(Dispatchers.IO) { gateway.refresh(credentials.refreshToken).also(store::save) }
            withContext(Dispatchers.IO) { block(fresh.accessToken) }
        }
    }
    override fun onCleared() { pairingJob?.cancel() }
}
