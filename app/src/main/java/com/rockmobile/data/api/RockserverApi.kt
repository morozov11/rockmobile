package com.rockmobile.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Small HTTP boundary so catalogue parsing and failure behaviour stay unit-testable. */
interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

/** Supplies the physical Wi-Fi network, if Android exposes it alongside the VPN. */
class WifiNetworkProvider(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun currentNetwork(): Network? = connectivityManager.allNetworks.firstOrNull { network ->
        connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }
}

class UrlConnectionTransport(
    private val wifiNetworkProvider: WifiNetworkProvider? = null,
) : HttpTransport {
    override fun post(url: String, bearerToken: String, jsonBody: String): HttpResponse {
        val parsedUrl = URI(url).toURL()
        // Android's VPN service normally becomes the default network. For local
        // 192.168.x.x targets, explicitly open the connection on physical Wi-Fi.
        // If Wi-Fi is unavailable, fall back to the normal route.
        val connection = ((if (parsedUrl.is192168Address()) {
            wifiNetworkProvider?.currentNetwork()?.openConnection(parsedUrl)
        } else null) ?: parsedUrl.openConnection()) as HttpURLConnection
        connection.apply {
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

private fun URL.is192168Address(): Boolean {
    val octets = host.split('.')
    return octets.size == 4 &&
        octets.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true } &&
        octets[0] == "192" && octets[1] == "168"
}

/** RockServer's existing protected search endpoint, used as the remote catalogue source. */
class RockserverApi(
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    fun search(baseUrl: String, bearerToken: String, query: String = "rock"): String {
        val endpoint = baseUrl.trim().trimEnd('/') + "/v1/search"
        val request = JSONObject().put("query", query).put("locale", "en-US").put("limit", 50)
        val response = transport.post(endpoint, bearerToken, request.toString())
        if (response.code !in 200..299) throw RockserverHttpException(response.code)
        return response.body
    }
}

class RockserverHttpException(val statusCode: Int) : IOException("Rockserver returned HTTP $statusCode")
