package com.rockmobile.account

import com.rockmobile.data.api.ApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared native device-session refresh used by account calls and personal-data sync.
 * Only the short-lived access token rotates; the durable device secret never does, and
 * token material is never logged.
 */
class NativeSessionManager(
    private val gateway: AccountGateway,
    private val store: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val renewMutex = Mutex()

    /** Device identity of the stored pairing, without any token renewal. */
    suspend fun storedDeviceId(): String? = withContext(ioDispatcher) { store.load()?.deviceId }

    /** Returns credentials whose access token is still usable, renewing first when expiry is near. */
    suspend fun currentSession(): NativeCredentials? {
        val stored = withContext(ioDispatcher) { store.load() } ?: return null
        if (!accessTokenNeedsRefresh(stored.accessExpiresAtMs, nowMs())) return stored
        return renew(stored.deviceId)
    }

    suspend fun renew(expectedDeviceId: String): NativeCredentials = renewMutex.withLock {
        val current = withContext(ioDispatcher) { store.load() } ?: throw IllegalStateException("No session")
        if (current.deviceId != expectedDeviceId) {
            return current
        }
        try {
            val fresh = withContext(ioDispatcher) { gateway.createDeviceSession(current.deviceId, current.deviceSecret) }
            withContext(ioDispatcher) { store.save(fresh) }
            fresh
        } catch (error: ApiError) {
            if (error.code == "device_credential_invalid") {
                val stillCurrent = withContext(ioDispatcher) { store.load() }
                if (stillCurrent?.deviceId == expectedDeviceId) {
                    SessionLog.credentialsCleared("device credential revoked")
                    withContext(ioDispatcher) { store.clear() }
                }
            }
            throw error
        }
    }
}
