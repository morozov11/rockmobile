package com.rockmobile.personalsync

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.HttpTransport
import com.rockmobile.data.api.RockserverApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSyncContractTest {
    @Test fun pushRequestSerializesSnakeCaseAndOmitsEmptyCollections() {
        val request = syncRequest(
            SyncBatch(
                FavouriteChangesDto(
                    listOf(FavouritePushDto("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "station-a", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z")),
                    listOf(RecordDeleteDto("01408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "2026-09-03T00:00:00Z")),
                ),
                null,
            ),
            sinceRevision = 42,
        )
        val body = JSONObject(PersonalSyncJson.codec.encodeToString(PersonalSyncRequestDto.serializer(), request))
        assertEquals(42L, body.getLong("since_revision"))
        assertFalse(body.has("history"))
        val upsert = body.getJSONObject("favourites").getJSONArray("upserts").getJSONObject(0)
        assertEquals("81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", upsert.getString("record_id"))
        assertEquals("station-a", upsert.getString("station_id"))
        assertEquals("2026-09-02T00:00:00Z", upsert.getString("updated_at"))
        val delete = body.getJSONObject("favourites").getJSONArray("deletes").getJSONObject(0)
        assertEquals("2026-09-03T00:00:00Z", delete.getString("updated_at"))
    }

    @Test fun historyPushCarriesMetadataObject() {
        val push = toHistoryPush(
            com.rockmobile.data.personal.HistoryEntry(
                "92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "station-a",
                "2026-09-05T10:00:00Z", "2026-09-05T10:05:00Z", "2026-09-05T10:05:00Z",
                null, 1_000, "A", source = "bundled",
            ),
        )
        val metadata = push.metadata
        assertEquals("A", metadata?.get("lastKnownName")?.toString()?.trim('"'))
        assertEquals("bundled", metadata?.get("source")?.toString()?.trim('"'))
        assertEquals(1_000L, push.playDurationMs)
    }

    @Test fun responseParsesRecordsAndTombstones() {
        val body = """
            {"server_revision": 11, "server_time": "2026-09-28T12:00:00Z",
             "favourites": {"records": [
                {"record_id": "81408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "station_id": "station-a",
                 "added_at": "2026-09-01T00:00:00Z", "updated_at": "2026-09-02T00:00:00Z"},
                {"record_id": "01408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "station_id": "station-b",
                 "added_at": "2026-09-01T00:00:00Z", "updated_at": "2026-09-04T00:00:00Z",
                 "deleted_at": "2026-09-04T00:00:00Z"}]},
             "history": {"records": [{
                "record_id": "92408a3e-5f0b-4d7a-9a1d-1f2f3d4d5e6f", "station_id": "station-a",
                "started_at": "2026-09-05T10:00:00Z", "last_played_at": "2026-09-05T10:05:00Z",
                "ended_at": "2026-09-05T10:05:00Z", "play_duration_ms": 300000,
                "metadata": {"lastKnownName": "A", "client": "rockmobile"},
                "updated_at": "2026-09-05T10:05:00Z"}]}}
        """.trimIndent()
        val response = PersonalSyncJson.codec.decodeFromString(PersonalSyncResponseDto.serializer(), body)
        assertEquals(11L, response.serverRevision)
        assertEquals(2, response.favourites.records.size)
        assertEquals("2026-09-04T00:00:00Z", response.favourites.records[1].deletedAt)
        val history = response.history.records.single()
        assertEquals(300_000L, history.playDurationMs)
        assertEquals("rockmobile", history.metadata?.get("client")?.toString()?.trim('"'))
    }

    @Test fun syncUsesTheCanonicalRouteAndDecodesSuccess() {
        val transport = ScriptedTransport(HttpResponse(200, """{"server_revision":3,"server_time":"2026-09-28T12:00:00Z","favourites":{"records":[]},"history":{"records":[]}}"""))
        val response = PersonalSyncApi(RockserverApi(transport)) { "https://server.test" }.sync("token-token-token", pullOnlyRequest(3))
        assertEquals("https://server.test/api/v1/sync", transport.url)
        assertEquals("token-token-token", transport.bearer)
        assertEquals(3L, response.serverRevision)
        assertTrue(JSONObject(transport.body).has("since_revision"))
    }

    @Test fun transportErrorsMapToTypedFailures() {
        fun api(code: Int, body: String = "{}") = PersonalSyncApi(RockserverApi(ScriptedTransport(HttpResponse(code, body)))) { "https://server.test" }
        assertEquals(PersonalSyncFailure.Unauthorized, runCatching { api(401).sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertEquals(PersonalSyncFailure.RateLimited, runCatching { api(429).sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertEquals(PersonalSyncFailure.Unavailable, runCatching { api(503).sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertEquals(PersonalSyncFailure.Rejected("favourites.upserts"), runCatching { api(422, """{"code":"validation_failed","details":{"field":"favourites.upserts"}}""").sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertEquals(PersonalSyncFailure.Rejected("request_shape"), runCatching { api(413).sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertEquals(PersonalSyncFailure.Rejected("quota_exceeded"), runCatching { api(422, """{"code":"quota_exceeded"}""").sync("t", pullOnlyRequest(0)) }.exceptionOrNull())
        assertTrue(runCatching { api(200, "not json").sync("t", pullOnlyRequest(0)) }.exceptionOrNull() is PersonalSyncFailure.Invalid)
    }

    private class ScriptedTransport(var post: HttpResponse) : HttpTransport {
        lateinit var url: String
        lateinit var bearer: String
        lateinit var body: String
        override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
            this.url = url; bearer = bearerToken; body = jsonBody; return post
        }
    }
}
