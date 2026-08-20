package com.rockmobile.data.api

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

/** Small HTTP boundary so catalogue parsing and failure behaviour stay unit-testable. */
interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResponse(connection.responseCode, body)
    }
}

/** RockServer's existing protected search endpoint, used as the remote catalogue source. */
class RockserverApi(
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    fun search(baseUrl: String, bearerToken: String, query: String = "rock"): String {
        val endpoint = baseUrl.trim().trimEnd('/') + "/v1/search"
        val request = JSONObject().put("query", query).put("locale", "en-US").put("limit", 50)
        val response = transport.post(endpoint, bearerToken, request.toString())
        Log.d("RockserverApi", "POST $endpoint -> ${response.code}, bytes=${response.body.length}")
        if (response.code !in 200..299) throw RockserverHttpException(response.code)
        return response.body
    }
}

class RockserverHttpException(val statusCode: Int) : IOException("Rockserver returned HTTP $statusCode")
