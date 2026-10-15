package com.rockmobile.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import com.rockmobile.data.api.ApiError
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

internal fun stationStreamUrl(baseUrl: String, stationId: String): String {
    require(stationId.isNotEmpty() && stationId.toByteArray(Charsets.UTF_8).size <= 128 &&
        stationId != "." && stationId != ".." && stationId.none(Char::isISOControl))
    val base = URI(baseUrl.trim().trimEnd('/'))
    require(base.scheme in listOf("http", "https") && base.host != null &&
        base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    val id = URLEncoder.encode(stationId, "UTF-8").replace("+", "%20")
    return "$base/api/v1/stations/$id/stream"
}

/** Credentials belong only to the configured server's station relay. */
internal fun isStationStreamUrl(baseUrl: String, url: String): Boolean = runCatching {
    val prefix = stationStreamUrl(baseUrl, "station").removeSuffix("station/stream")
    val uri = URI(url)
    val id = url.removePrefix(prefix).removeSuffix("/stream")
    url.startsWith(prefix) && url.endsWith("/stream") && id.isNotEmpty() && '/' !in id &&
        uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun stationStreamHeaders(
    baseUrl: String,
    url: String,
    requestHeaders: Map<String, String>,
    accessToken: suspend () -> String?,
): Map<String, String> {
    if (!isStationStreamUrl(baseUrl, url)) throw IOException("Expected a RockServer station stream")
    val token = try {
        accessToken()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ApiError) {
        if (error.status in listOf(401, 403)) throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        throw IOException("Could not renew RockServer session", error)
    } catch (error: Exception) {
        throw IOException("Could not renew RockServer session", error)
    } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
    return requestHeaders + ("Authorization" to "Bearer $token")
}
