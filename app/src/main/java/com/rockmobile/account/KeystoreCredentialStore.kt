package com.rockmobile.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    fun load(): NativeCredentials?
    fun save(credentials: NativeCredentials)
    fun loadProfile(): AccountProfile? = null
    fun saveProfile(profile: AccountProfile) = Unit
    fun loadPendingPairing(): PendingPairingSnapshot? = null
    fun savePendingPairing(snapshot: PendingPairingSnapshot) = Unit
    fun clearPendingPairing() = Unit
    fun clear()
}

/** Encrypted private file; the AES key is non-exportable and held in Android Keystore. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val file = File(context.noBackupFilesDir, "native_session.bin")
    private val recoveryFile = File(context.noBackupFilesDir, "native_session.recovery.bin")
    private val pairingFile = File(context.noBackupFilesDir, "pending_pairing.bin")

    override fun load(): NativeCredentials? {
        val fromMain = readCredentials(file)
        if (fromMain != null) return fromMain
        val fromRecovery = readCredentials(recoveryFile)
        if (fromRecovery != null) {
            SessionLog.probeOffline("promoting recovered native session credentials")
            runCatching { save(fromRecovery) }
            return fromRecovery
        }
        return null
    }

    override fun loadProfile(): AccountProfile? = try {
        readJson(file)?.optJSONObject("profile")?.let(::profile)
    } catch (_: Exception) {
        null
    }

    override fun save(credentials: NativeCredentials) {
        val recoveryBody = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
        }
        writeEncrypted(recoveryFile, recoveryBody)
        updateJson {
            put("access_token", credentials.accessToken).put("refresh_token", credentials.refreshToken)
        }
        recoveryFile.delete()
        val persisted = readCredentials(file)
        if (persisted?.accessToken != credentials.accessToken ||
            persisted.refreshToken != credentials.refreshToken
        ) {
            error("Failed to persist native session credentials")
        }
    }

    override fun saveProfile(profile: AccountProfile) = updateJson { put("profile", profileJson(profile)) }

    override fun loadPendingPairing(): PendingPairingSnapshot? = try {
        readPairingJson()?.let(::pendingPairingSnapshot)
    } catch (_: Exception) {
        clearPendingPairing()
        null
    }

    override fun savePendingPairing(snapshot: PendingPairingSnapshot) {
        pairingFile.parentFile?.mkdirs()
        pairingFile.writeBytes(encrypt(pendingPairingJson(snapshot).toString().toByteArray()))
    }

    override fun clearPendingPairing() { pairingFile.delete() }

    override fun clear() {
        file.delete()
        recoveryFile.delete()
        pairingFile.delete()
    }

    private fun readCredentials(target: File): NativeCredentials? = try {
        readJson(target)?.let {
            NativeCredentials(it.getString("access_token"), it.getString("refresh_token"))
        }
    } catch (_: Exception) {
        null
    }

    private fun readJson(target: File = file): JSONObject? =
        if (!target.exists()) null else JSONObject(decrypt(target.readBytes()).toString(Charsets.UTF_8))

    private fun readPairingJson(): JSONObject? =
        if (!pairingFile.exists()) null else JSONObject(decrypt(pairingFile.readBytes()).toString(Charsets.UTF_8))

    private fun updateJson(update: JSONObject.() -> Unit) {
        val body = readJson(file) ?: JSONObject()
        body.update()
        writeEncrypted(file, body)
    }

    private fun writeEncrypted(target: File, body: JSONObject) {
        target.parentFile?.mkdirs()
        val bytes = encrypt(body.toString().toByteArray())
        val temp = File(target.parentFile, "${target.name}.new")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        if (!temp.renameTo(target)) {
            java.io.FileOutputStream(target).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            temp.delete()
        }
    }

    private fun profileJson(profile: AccountProfile) = JSONObject().apply {
        put("user_id", profile.userId)
        put("session_id", profile.sessionId)
        put("device_id", profile.deviceId)
        put("account_display_name", profile.accountDisplayName)
        put("device_display_name", profile.deviceDisplayName)
        put("device_type", profile.deviceType)
        profile.createdAt?.let { put("created_at", it) }
        profile.deviceCreatedAt?.let { put("device_created_at", it) }
        profile.lastSeenAt?.let { put("last_seen_at", it) }
    }

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

    private fun pendingPairingJson(snapshot: PendingPairingSnapshot) = JSONObject().apply {
        put("request_id", snapshot.requestId)
        put("desktop_token", snapshot.desktopToken)
        put("approval_secret", snapshot.approvalSecret)
        put("short_code", snapshot.shortCode)
        put("verification_phrase", snapshot.verificationPhrase)
        put("device_display_name", snapshot.deviceDisplayName)
        put("device_type", snapshot.deviceType)
        put("expires_at", snapshot.expiresAt)
        put("status", snapshot.status)
        put("deadline_ms", snapshot.deadlineMs)
    }

    private fun pendingPairingSnapshot(body: JSONObject) = PendingPairingSnapshot(
        requestId = body.getString("request_id"),
        desktopToken = body.getString("desktop_token"),
        approvalSecret = body.getString("approval_secret"),
        shortCode = body.getString("short_code"),
        verificationPhrase = body.getString("verification_phrase"),
        deviceDisplayName = body.getString("device_display_name"),
        deviceType = body.getString("device_type"),
        expiresAt = body.optString("expires_at"),
        status = body.optString("status"),
        deadlineMs = body.getLong("deadline_ms"),
    )

    private fun key() = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()) as javax.crypto.SecretKey

    private fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        iv + doFinal(plain)
    }

    private fun decrypt(ciphertext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(ciphertext.size > 12)
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    private companion object { const val KEY_ALIAS = "rockmobile_native_session" }
}
