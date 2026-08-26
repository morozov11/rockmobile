package com.rockmobile.data.stations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rockmobile.domain.model.Station
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct station icon loading for the pre-RockServer-icon MVP.
 * Network I/O, decode, and disk cache stay off the UI thread; callers pass an already-resolved [Station].
 */
object StationIconLoader {
    private const val CACHE_MAGIC = "RMSTICON1"
    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 16 * 1024
    private const val MAX_ICON_SIDE = 64

    /** Only URL the MVP may fetch: explicit favicon/logo, else conventional `/favicon.ico` on the official homepage. */
    fun sourceUrl(station: Station): String? {
        validHttpUrl(station.faviconUrl)?.let { return it }
        val homepage = validHttpUrl(station.homepageUrl) ?: return null
        return runCatching {
            val base = URI(homepage)
            URI(base.scheme, base.authority, "/favicon.ico", null, null).toString()
        }.getOrNull()?.takeIf { validHttpUrl(it) != null }
    }

    fun cacheStem(station: Station): String {
        val material = station.id.ifEmpty { station.streamUrl }.toByteArray(Charsets.UTF_8)
        return buildString(material.size * 2 + 3) {
            append("v1-")
            for (byte in material) append("%02x".format(byte))
        }
    }

    /** Load a valid cached thumbnail or fetch/decode/cache once. Failures return null (letter tile). */
    fun loadOrFetch(context: Context, station: Station): Bitmap? {
        val source = sourceUrl(station) ?: return null
        val root = File(context.cacheDir, "station-icons")
        val path = File(root, "${cacheStem(station)}.bin")
        readCache(path, source)?.let { return it }
        val bytes = fetchBytes(source) ?: return null
        val bitmap = decodeIcon(bytes) ?: return null
        writeCache(path, source, bitmap)
        return bitmap
    }

    internal fun validHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        return uri.toString()
    }

    private fun fetchBytes(source: String): ByteArray? {
        return runCatching {
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", "Rockmobile station icon/1")
            }
            if (connection.responseCode !in 200..299) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_DOWNLOAD_BYTES) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count <= 0) break
                    if (output.size() + count > MAX_DOWNLOAD_BYTES) return null
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > 1024 || bounds.outHeight > 1024) return null
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_ICON_SIDE)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (decoded.width <= MAX_ICON_SIDE && decoded.height <= MAX_ICON_SIDE) return decoded
        val scale = minOf(MAX_ICON_SIDE.toFloat() / decoded.width, MAX_ICON_SIDE.toFloat() / decoded.height, 1f)
        val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide * 2 || height / sample > maxSide * 2) sample *= 2
        return sample
    }

    private fun readCache(path: File, source: String): Bitmap? {
        val bytes = runCatching { path.readBytes() }.getOrNull() ?: return null
        if (bytes.size > MAX_CACHE_BYTES || bytes.size < CACHE_MAGIC.length + 16) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(CACHE_MAGIC.length).also { buffer.get(it) }
        if (!magic.contentEquals(CACHE_MAGIC.toByteArray(Charsets.US_ASCII))) return null
        val width = buffer.int
        val height = buffer.int
        val sourceLen = buffer.int
        val rgbaLen = buffer.int
        if (width <= 0 || height <= 0 || width > MAX_ICON_SIDE || height > MAX_ICON_SIDE) return null
        if (sourceLen <= 0 || sourceLen > MAX_SOURCE_BYTES) return null
        if (rgbaLen != width * height * 4) return null
        if (buffer.remaining() < sourceLen + rgbaLen) return null
        val cachedSource = ByteArray(sourceLen).also { buffer.get(it) }
        if (!cachedSource.contentEquals(source.toByteArray(Charsets.UTF_8))) return null
        val rgba = ByteArray(rgbaLen).also { buffer.get(it) }
        if (buffer.hasRemaining()) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
        }.getOrNull()
    }

    private fun writeCache(path: File, source: String, bitmap: Bitmap) {
        if (source.length > MAX_SOURCE_BYTES) return
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || width > MAX_ICON_SIDE || height > MAX_ICON_SIDE) return
        val rgba = ByteArray(width * height * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(CACHE_MAGIC.length + 16 + sourceBytes.size + rgba.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(CACHE_MAGIC.toByteArray(Charsets.US_ASCII))
        payload.putInt(width)
        payload.putInt(height)
        payload.putInt(sourceBytes.size)
        payload.putInt(rgba.size)
        payload.put(sourceBytes)
        payload.put(rgba)
        runCatching {
            path.parentFile?.mkdirs()
            val temporary = File(path.parentFile, "${path.name}.tmp-${System.nanoTime()}")
            temporary.writeBytes(payload.array())
            if (!temporary.renameTo(path)) {
                path.delete()
                temporary.renameTo(path)
            }
        }
    }
}
