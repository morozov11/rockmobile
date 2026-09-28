package com.rockmobile.personalsync

import com.rockmobile.data.api.HttpResponse
import com.rockmobile.data.api.RockserverApi
import org.json.JSONObject
import java.io.IOException

/** Typed sync-cycle failures; none of them ever carries a token or a record payload. */
internal sealed class PersonalSyncFailure(message: String) : IOException(message) {
    data object Unauthorized : PersonalSyncFailure("account session unauthorized")
    data object RateLimited : PersonalSyncFailure("sync rate limited")
    /** `details.field` (or the error code) of a 422 / shaped 400 / 413; never the payload. */
    data class Rejected(val field: String) : PersonalSyncFailure("sync batch rejected: $field")
    data object Unavailable : PersonalSyncFailure("sync service unavailable")
    data class Invalid(val detail: String) : PersonalSyncFailure("sync response invalid: $detail")
    data class LocalApply(val detail: String) : PersonalSyncFailure("local apply failed: $detail")
}

/** One `POST /api/v1/sync` round trip over the native device session; bearer material is never logged. */
internal class PersonalSyncApi(private val api: RockserverApi, private val baseUrlProvider: () -> String) {
    fun sync(accessToken: String, request: PersonalSyncRequestDto): PersonalSyncResponseDto {
        val body = PersonalSyncJson.codec.encodeToString(PersonalSyncRequestDto.serializer(), request)
        val response = api.postJson(baseUrlProvider(), "${RockserverApi.API_V1_PREFIX}/sync", accessToken, body)
        if (response.code !in 200..299) throw failureFrom(response)
        return try {
            PersonalSyncJson.codec.decodeFromString(PersonalSyncResponseDto.serializer(), response.body)
        } catch (error: Exception) {
            throw PersonalSyncFailure.Invalid(error.javaClass.simpleName)
        }
    }

    private fun failureFrom(response: HttpResponse): PersonalSyncFailure = when (response.code) {
        401 -> PersonalSyncFailure.Unauthorized
        429 -> PersonalSyncFailure.RateLimited
        422 -> PersonalSyncFailure.Rejected(rejectedField(response.body))
        400, 413 -> PersonalSyncFailure.Rejected("request_shape")
        else -> PersonalSyncFailure.Unavailable
    }

    private fun rejectedField(body: String): String = runCatching {
        val error = JSONObject(body)
        error.optJSONObject("details")?.optString("field")?.takeIf(String::isNotEmpty)
            ?: error.optString("code").takeIf(String::isNotEmpty)
    }.getOrNull() ?: "unknown"
}
