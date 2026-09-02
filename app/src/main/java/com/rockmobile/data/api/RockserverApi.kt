package com.rockmobile.data.api

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Small HTTP boundary so catalogue parsing and failure behaviour stay unit-testable. */
interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse

    @Throws(IOException::class)
    fun get(url: String, bearerToken: String): HttpResponse =
        throw UnsupportedOperationException("GET is not supported by this transport")

    @Throws(IOException::class)
    fun delete(url: String, bearerToken: String): HttpResponse =
        throw UnsupportedOperationException("DELETE is not supported by this transport")
}

data class HttpResponse(val code: Int, val body: String)

/** Safe representation of a server error. Never retain the response body or request metadata. */
class ApiError(
    val status: Int,
    val code: String? = null,
    val requestId: String? = null,
) : IOException("Rockserver returned HTTP $status") {
    val statusCode: Int get() = status
    companion object {
        fun from(response: HttpResponse): ApiError {
            val error = runCatching { JSONObject(response.body) }.getOrNull()
            return ApiError(
                status = response.code,
                code = error?.optString("code")?.trim()?.takeIf(String::isNotEmpty),
                requestId = error?.optString("request_id")?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }
}

class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
        return request("POST", url, bearerToken, jsonBody)
    }

    override fun get(url: String, bearerToken: String): HttpResponse = request("GET", url, bearerToken)
    override fun delete(url: String, bearerToken: String): HttpResponse = request("DELETE", url, bearerToken)

    private fun request(method: String, url: String, bearerToken: String, jsonBody: String? = null): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = jsonBody != null
            if (jsonBody != null) setRequestProperty("Content-Type", "application/json")
            if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        if (jsonBody != null) connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResponse(connection.responseCode, body)
    }
}

/** RockServer's existing public search endpoint. It is never used for the initial catalogue. */
class RockserverApi(
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    fun search(baseUrl: String, bearerToken: String, query: String): String {
        val endpoint = endpoint(baseUrl, "$API_V1_PREFIX/search")
        val request = JSONObject().put("query", query).put("locale", "en-US").put("limit", 20)
        val response = transport.post(endpoint, bearerToken, request.toString())
        if (response.code !in 200..299) throw ApiError.from(response)
        return response.body
    }

    /** Reads one page of the public station catalogue for server-backed filter options. */
    fun catalogPage(baseUrl: String, bearerToken: String, cursor: String? = null): String {
        val query = buildString {
            append("?limit=50")
            cursor?.trim()?.takeIf(String::isNotEmpty)?.let {
                append("&cursor=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }
        val response = transport.get(endpoint(baseUrl, "$API_V1_PREFIX/catalog/stations$query"), bearerToken)
        if (response.code !in 200..299) throw ApiError.from(response)
        return response.body
    }

    fun post(baseUrl: String, route: String, bearerToken: String = "", body: JSONObject = JSONObject()): HttpResponse =
        transport.post(endpoint(baseUrl, route), bearerToken, body.toString())

    fun get(baseUrl: String, route: String, bearerToken: String): HttpResponse =
        transport.get(endpoint(baseUrl, route), bearerToken)

    fun delete(baseUrl: String, route: String, bearerToken: String): HttpResponse =
        transport.delete(endpoint(baseUrl, route), bearerToken)

    private fun endpoint(baseUrl: String, route: String) = baseUrl.trim().trimEnd('/') + route

    companion object {
        /** Canonical RockServer API prefix for all public and native client routes. */
        const val API_V1_PREFIX = "/api/v1"
    }
}
