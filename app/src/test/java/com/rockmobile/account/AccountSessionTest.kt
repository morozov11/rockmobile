package com.rockmobile.account

import com.rockmobile.isRockmobileReturnTarget
import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.ApiError
import com.rockmobile.ui.stations.rockMobileLogoDescription
import com.rockmobile.ui.stations.rockMobileTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionTest {
    @Test fun rockmobileReturnAppLink_accepts_only_the_exact_credentialFreeRoute() {
        assertTrue(isRockmobileReturnTarget("https", "alex.vault57.ru", "/return/rockmobile", null, null))
        assertFalse(isRockmobileReturnTarget("https", "alex.vault57.ru", "/", null, "secret=synthetic"))
        assertFalse(isRockmobileReturnTarget("https", "alex.vault57.ru", "/return/rockmobile", "code=AB12CD34", null))
        assertFalse(isRockmobileReturnTarget("https", "alex.vault57.ru", "/return/rockmobile", null, "secret=synthetic"))
        assertFalse(isRockmobileReturnTarget("https", "not-alex.vault57.ru", "/return/rockmobile", null, null))
    }

    @Test fun pairingQr_hasFourModuleQuietZone_integerScale_andSyntheticLink() {
        val link = "https://server.test/?code=AB12CD34#secret=synthetic-proof"
        val matrix = pairingQrMatrix(link)
        val firstBlackX = (0 until matrix.width).first { x -> (0 until matrix.height).any { y -> matrix[x, y] } }
        val firstBlackY = (0 until matrix.height).first { y -> (0 until matrix.width).any { x -> matrix[x, y] } }
        assertTrue(firstBlackX >= QR_QUIET_ZONE_MODULES)
        assertTrue(firstBlackY >= QR_QUIET_ZONE_MODULES)
        assertEquals(6, pairingQrModulePixels(matrix, matrix.width * 6 + 5))
        assertTrue(link.startsWith("https://server.test/?code="))
    }

    @Test fun deviceName_defaultsAndValidatesAgainstServerBounds() {
        assertEquals("RMX5056", defaultDeviceDisplayName("RMX5056"))
        assertEquals("Pixel 9", defaultDeviceDisplayName("Pixel 9"))
        assertEquals("Android device", defaultDeviceDisplayName(" "))
        assertTrue(validateDeviceDisplayName("  phone  ") == null)
        assertTrue(validateDeviceDisplayName(" ") != null)
        assertTrue(validateDeviceDisplayName("я".repeat(129)) != null)
        assertEquals("RockMobile — Pixel 9", presentDeviceDisplayName("rockmobile_android", "Pixel 9"))
        assertEquals("RockMobile — Pixel 9", presentDeviceDisplayName("rockmobile_android", "RockMobile — Pixel 9"))
        assertEquals("RockCast — Office", presentDeviceDisplayName("windows", "RockCast — Office"))
        assertEquals("RockMobile — Pixel 9", presentDeviceDisplayName("rockmobile_android", "RockCast — Pixel 9"))
    }

    @Test fun accountIdentity_usesRockMobileAndShowsInstalledBuild() {
        assertEquals("Подключите RockMobile к существующему Rock-аккаунту.", disconnectedPrimaryCopy())
        assertEquals("Радио и сохранённые станции работают без аккаунта.", disconnectedSecondaryCopy())
        assertEquals("RockMobile", rockMobileTitle())
        assertEquals("RockMobile logo", rockMobileLogoDescription())
        assertTrue(visibleBuild().matches(Regex("0\\.1\\.5 \\([0-9a-f]{7}\\)")))
    }

    @Test fun pairingCompletion_sendsOnlyDesktopProof_neverUserId() {
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "AMBER-DAWN")
        val body = pairing.completionBody()
        assertEquals(setOf("desktop_token"), body.keys().asSequence().toSet())
        assertFalse(body.has("user_id"))
    }

    @Test fun createPairing_usesG1DeviceMetadataAndKeepsBrowserContext() {
        val transport = ScriptedTransport(post = HttpResponse(201, createdPairing))
        val pairing = RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }
            .createPairing("Pixel 9")

        assertEquals("Pixel 9", pairing.deviceDisplayName)
        assertEquals("rockmobile_android", pairing.deviceType)
        assertEquals("https://server.test/?code=AB12CD34#secret=secret", pairing.browserLink("https://server.test"))
        val body = JSONObject(transport.body)
        assertEquals("Pixel 9", body.getString("device_display_name"))
        assertEquals("rockmobile_android", body.getString("device_type"))
        assertFalse(body.has("device_name"))
        assertFalse(body.has("user_id"))
    }

    @Test fun completePairing_readsG1DisplayContextAndSendsDesktopProof() {
        val transport = ScriptedTransport(post = HttpResponse(200, completion))
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "AMBER-DAWN")
        val result = RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }.completePairing(pairing)

        assertEquals("device", result.first.deviceId)
        assertEquals("Alex's Rock account", result.first.accountDisplayName)
        assertEquals("RockMobile — Pixel 9", result.first.deviceDisplayName)
        assertEquals("https://server.test/v1/pairing-requests/request/complete", transport.url)
        assertEquals("d".repeat(16), JSONObject(transport.body).getString("desktop_token"))
        assertFalse(JSONObject(transport.body).has("user_id"))
    }

    @Test fun devicesAndRevoke_followNativeG3RoutesWithoutRenderingIds() {
        val transport = ScriptedTransport(
            post = HttpResponse(200, tokens),
            get = HttpResponse(200, devices),
            delete = HttpResponse(204, ""),
        )
        val gateway = RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }
        val listed = gateway.devices("a".repeat(16))
        gateway.revokeDevice("a".repeat(16), "device-2")

        assertEquals("RockCast — Office", listed.single().deviceDisplayName)
        assertEquals("windows", listed.single().deviceType)
        assertEquals("https://server.test/v1/devices/device-2", transport.url)
        assertEquals("a".repeat(16), transport.bearer)
    }

    @Test fun refreshAndLogout_followNativeContract() {
        val transport = ScriptedTransport(post = HttpResponse(200, tokens))
        val gateway = RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }
        assertEquals("a".repeat(16), gateway.refresh("r".repeat(16)).accessToken)
        assertEquals("https://server.test/v1/auth/refresh", transport.url)
        assertEquals("r".repeat(16), JSONObject(transport.body).getString("refresh_token"))

        transport.post = HttpResponse(401, "{}")
        gateway.logout("a".repeat(16))
        assertEquals("https://server.test/v1/auth/logout", transport.url)
        assertEquals("a".repeat(16), transport.bearer)
    }

    @Test fun unapprovedOrOfflineCompletion_doesNotProduceCredentials() {
        val transport = ScriptedTransport(post = HttpResponse(401, "{}"))
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "AMBER-DAWN")
        val result = runCatching { RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }.completePairing(pairing) }
        assertTrue(result.exceptionOrNull() is ApiError)
    }

    @Test fun gateway_mapsPendingCompletionToRetryableStatus() {
        val transport = ScriptedTransport(post = HttpResponse(202, "{}"))
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "AMBER-DAWN")
        val error = runCatching {
            RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }.completePairing(pairing)
        }.exceptionOrNull() as ApiError
        assertEquals(202, error.statusCode)
    }

    @Test fun pairingPoll_retriesOnlyPendingApproval_beforeDeadline() {
        assertTrue(shouldContinuePairing(ApiError(409, "pairing_pending"), nowMs = 100, deadlineMs = 101))
        assertFalse(shouldContinuePairing(ApiError(202), nowMs = 100, deadlineMs = 101))
        assertFalse(shouldContinuePairing(ApiError(202, "pairing_pending"), nowMs = 101, deadlineMs = 101))
        assertFalse(shouldContinuePairing(ApiError(503), nowMs = 100, deadlineMs = 101))
        assertFalse(shouldContinuePairing(ApiError(410), nowMs = 100, deadlineMs = 101))
    }

    @Test fun apiError_parsesOnlySafeTuple_andSupportsOldOrMalformedBodies() {
        val parsed = ApiError.from(HttpResponse(409, """{"code":"device_limit_reached","request_id":"req-1","message":"secret","details":{"token":"secret"}}"""))
        assertEquals(409, parsed.statusCode)
        assertEquals("device_limit_reached", parsed.code)
        assertEquals("req-1", parsed.requestId)
        assertEquals(null, ApiError.from(HttpResponse(410, "not json")).code)
        assertEquals(null, ApiError.from(HttpResponse(410, "{}")).requestId)
    }

    @Test fun pairingErrors_preferCanonicalCode_thenUseNarrowStatusFallbacks() {
        assertEquals("Подключение не подтверждено", pairingErrorMessage(ApiError(404, "pairing_rejected")))
        assertEquals("Ссылка истекла", pairingErrorMessage(ApiError(404, "pairing_expired")))
        assertTrue(pairingErrorMessage(ApiError(404, "client_upgrade_required")).contains("Обновите"))
        assertTrue(pairingErrorMessage(ApiError(503, "pairing_unavailable")).contains("недоступен"))
        assertTrue(pairingErrorMessage(ApiError(404, "unknown")).contains("Не удалось"))
        assertTrue(pairingErrorMessage(ApiError(409)).contains("лимит"))
        assertTrue(pairingErrorMessage(ApiError(410)).contains("недоступен"))
    }

    @Test fun viewModel_pairingWaitsResumesAndCompletesWithAccountName() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeGateway()
            val store = MemoryStore()
            val viewModel = AccountViewModel(gateway, store, dispatcher, { testScheduler.currentTime }, 1_000, 100)
            viewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            assertTrue(viewModel.state.value is AccountUiState.Pairing)

            gateway.approved = true
            viewModel.resumePairing()
            advanceTimeBy(100)
            runCurrent()

            val connected = viewModel.state.value as AccountUiState.ConnectedFirstTime
            assertEquals("Alex's Rock account", connected.profile.accountDisplayName)
            assertEquals("Alex's Rock account", store.profile?.accountDisplayName)
            viewModel.openDevices()
            assertTrue(viewModel.state.value is AccountUiState.Connected)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_startingBlocksDuplicateCreateAndThenWaits() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeGateway()
            val viewModel = AccountViewModel(gateway, MemoryStore(), dispatcher, { testScheduler.currentTime }, 1_000, 100)
            viewModel.connect("RMX5056")
            viewModel.connect("Second request")
            assertEquals(AccountUiState.Starting, viewModel.state.value)
            runCurrent()
            assertTrue(viewModel.state.value is AccountUiState.Pairing)
            assertEquals(1, gateway.createCalls)
            viewModel.cancelPairing()
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_cancelStopsPairingWithoutSavingCredentials() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeGateway()
            val store = MemoryStore()
            val viewModel = AccountViewModel(gateway, store, dispatcher, { testScheduler.currentTime }, 1_000, 100)
            viewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            viewModel.cancelPairing()
            runCurrent()
            assertEquals(AccountUiState.Disconnected, viewModel.state.value)
            assertEquals(null, store.credentials)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_timeoutIsClearAndDoesNotCreateSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val store = MemoryStore()
            val viewModel = AccountViewModel(FakeGateway(), store, dispatcher, { testScheduler.currentTime }, 300, 100)
            viewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            advanceUntilIdle()
            assertEquals("Ссылка истекла", (viewModel.state.value as AccountUiState.Error).message)
            assertEquals(null, store.credentials)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_alreadyConnectedAndUnavailableHaveUserSafeStates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val already = FakeGateway().apply { completionError = ApiError(409) }
            val alreadyViewModel = AccountViewModel(already, MemoryStore(), dispatcher, { testScheduler.currentTime }, 1_000, 100)
            alreadyViewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            assertTrue((alreadyViewModel.state.value as AccountUiState.Error).message.contains("лимит устройств"))

            val unavailable = FakeGateway().apply { createError = IOException("offline") }
            val unavailableViewModel = AccountViewModel(unavailable, MemoryStore(), dispatcher, { testScheduler.currentTime }, 1_000, 100)
            unavailableViewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            assertTrue((unavailableViewModel.state.value as AccountUiState.Error).message.contains("недоступен"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_deviceListFailureDoesNotBlockSuccessfulPairing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeGateway().apply {
                approved = true
                devicesError = ApiError(404)
            }
            val viewModel = AccountViewModel(gateway, MemoryStore(), dispatcher, { testScheduler.currentTime }, 1_000, 100)
            viewModel.connect("RockMobile — Pixel 9")
            runCurrent()
            val connected = viewModel.state.value as AccountUiState.ConnectedFirstTime
            assertFalse(connected.devicesAvailable)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModel_refreshLogoutAndRevokeUseStoredSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeGateway().apply { approved = true }
            val store = MemoryStore().apply { credentials = NativeCredentials("o".repeat(16), "r".repeat(16)) }
            val viewModel = AccountViewModel(gateway, store, dispatcher, { testScheduler.currentTime }, 1_000, 100)
            runCurrent()
            assertEquals("new-access-token-1234", store.credentials?.accessToken)
            viewModel.revoke("other-device")
            runCurrent()
            assertEquals("other-device", gateway.revokedDeviceId)
            viewModel.logout()
            runCurrent()
            assertTrue(gateway.loggedOut)
            assertEquals(null, store.credentials)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class ScriptedTransport(
        var post: HttpResponse,
        var get: HttpResponse = HttpResponse(404, "{}"),
        var delete: HttpResponse = HttpResponse(404, "{}"),
    ) : HttpTransport {
        lateinit var url: String
        lateinit var bearer: String
        lateinit var body: String
        override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
            this.url = url; bearer = bearerToken; body = jsonBody; return post
        }
        override fun get(url: String, bearerToken: String): HttpResponse {
            this.url = url; bearer = bearerToken; return get
        }
        override fun delete(url: String, bearerToken: String): HttpResponse {
            this.url = url; bearer = bearerToken; return delete
        }
    }

    private class MemoryStore : CredentialStore {
        var credentials: NativeCredentials? = null
        var profile: AccountProfile? = null
        override fun load() = credentials
        override fun save(credentials: NativeCredentials) { this.credentials = credentials }
        override fun loadProfile() = profile
        override fun saveProfile(profile: AccountProfile) { this.profile = profile }
        override fun clear() { credentials = null; profile = null }
    }

    private class FakeGateway : AccountGateway {
        var createCalls = 0
        var approved = false
        var createError: Throwable? = null
        var completionError: Throwable? = null
        var devicesError: Throwable? = null
        var revokedDeviceId: String? = null
        var loggedOut = false
        private val profile = AccountProfile(
            userId = "owner",
            sessionId = "session",
            deviceId = "device",
            accountDisplayName = "Alex's Rock account",
            deviceDisplayName = "RockMobile — Pixel 9",
            deviceType = "rockmobile_android",
        )
        override fun createPairing(deviceName: String): PairingRequest {
            createCalls++
            createError?.let { throw it }
            return PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "AMBER-DAWN", deviceName, "rockmobile_android")
        }
        override fun completePairing(pairing: PairingRequest): Pair<AccountProfile, NativeCredentials> {
            completionError?.let { throw it }
            if (!approved) throw ApiError(202, "pairing_pending")
            return profile to NativeCredentials("a".repeat(16), "b".repeat(16))
        }
        override fun refresh(refreshToken: String) = NativeCredentials("new-access-token-1234", "new-refresh-token-1234")
        override fun profile(accessToken: String) = profile
        override fun devices(accessToken: String): List<AccountDevice> {
            devicesError?.let { throw it }
            return listOf(AccountDevice("device", "owner", "RockMobile — Pixel 9", "rockmobile_android"))
        }
        override fun revokeDevice(accessToken: String, deviceId: String) { revokedDeviceId = deviceId }
        override fun logout(accessToken: String) { loggedOut = true }
    }

    private companion object {
        val createdPairing = """
            {"pairing_request_id":"request","desktop_token":"${"d".repeat(16)}","approval_secret":"secret","short_code":"AB12CD34","verification_phrase":"AMBER-DAWN","device_display_name":"Pixel 9","device_type":"rockmobile_android","expires_at":"2099-08-28T12:00:00Z","status":"pending"}
        """.trimIndent()
        val tokens = """{"access_token":"${"a".repeat(16)}","refresh_token":"${"r".repeat(16)}"}"""
        val completion = """{"user_id":"user","device_id":"device","session_id":"session","access_token":"${"a".repeat(16)}","refresh_token":"${"r".repeat(16)}","account_display_name":"Alex's Rock account","device_display_name":"RockMobile — Pixel 9","device_type":"rockmobile_android"}"""
        val devices = """{"devices":[{"device_id":"device-2","user_id":"owner","device_display_name":"RockCast — Office","device_type":"windows","created_at":"2026-08-28T12:00:00Z"}]}"""
    }
}
