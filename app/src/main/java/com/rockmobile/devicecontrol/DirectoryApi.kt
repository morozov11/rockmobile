package com.rockmobile.devicecontrol

import com.rockmobile.data.api.ApiError
import com.rockmobile.data.api.RockserverApi
import kotlinx.serialization.json.Json

/** Authenticated REST directory read; bearer material is never retained or logged. */
internal class DeviceControlDirectoryApi(private val api: RockserverApi, private val baseUrlProvider: () -> String) {
    internal fun baseUrl(): String = baseUrlProvider()

    fun load(accessToken: String): DirectoryDto {
        val response = api.get(baseUrlProvider(), "${RockserverApi.API_V1_PREFIX}/device-control/directory", accessToken)
        if (response.code !in 200..299) throw ApiError.from(response)
        return DirectoryJson.codec.decodeFromString<DirectoryDto>(response.body).also {
            require(it.protocolVersion == 1 && it.revision >= 1 && it.devices.size <= 50) { "Invalid directory snapshot" }
        }
    }
}
