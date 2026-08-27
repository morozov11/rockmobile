package com.rockmobile.account

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.api.RockserverHttpException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionTest {
    @Test fun pairingCompletion_sendsOnlyDesktopProof_neverUserId() {
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "amber dawn")
        val body = pairing.completionBody()
        assertEquals(setOf("desktop_token"), body.keys().asSequence().asIterable().toSet())
        assertFalse(body.has("user_id"))
    }

    @Test fun completePairing_usesDesktopProofAndSavesNoRequestOwner() {
        val transport = ScriptedTransport(post = HttpResponse(200, completion))
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "amber dawn")
        val result = RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }.completePairing(pairing)
        assertEquals("device", result.first.deviceId)
        assertEquals("d".repeat(16), JSONObject(transport.body).getString("desktop_token"))
        assertFalse(JSONObject(transport.body).has("user_id"))
        assertEquals("https://server.test/v1/pairing-requests/request/complete", transport.url)
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
        val pairing = PairingRequest("request", "d".repeat(16), "secret", "AB12CD34", "amber dawn")
        val result = runCatching { RockserverAccountGateway(RockserverApi(transport)) { "https://server.test" }.completePairing(pairing) }
        assertTrue(result.exceptionOrNull() is RockserverHttpException)
    }

    @Test fun pairingPoll_retriesOnlyPendingApproval_beforeDeadline() {
        val pending = RockserverHttpException(401)
        assertTrue(shouldContinuePairing(pending, nowMs = 100, deadlineMs = 101))
        assertFalse(shouldContinuePairing(pending, nowMs = 101, deadlineMs = 101))
        assertFalse(shouldContinuePairing(RockserverHttpException(503), nowMs = 100, deadlineMs = 101))
    }

    private class ScriptedTransport(var post: HttpResponse) : HttpTransport {
        lateinit var url: String
        lateinit var bearer: String
        lateinit var body: String
        override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
            this.url = url; bearer = bearerToken; body = jsonBody; return post
        }
    }
    private companion object {
        val tokens = """{"access_token":"${"a".repeat(16)}","refresh_token":"${"r".repeat(16)}"}"""
        val completion = """{"user_id":"user","device_id":"device","session_id":"session","access_token":"${"a".repeat(16)}","refresh_token":"${"r".repeat(16)}"}"""
    }
}
