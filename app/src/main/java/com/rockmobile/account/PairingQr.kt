package com.rockmobile.account

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Instant

internal const val QR_QUIET_ZONE_MODULES = 4

/** QR input is transient: callers must never store or log [link]. */
internal fun pairingQrMatrix(link: String): BitMatrix = MultiFormatWriter().encode(
    link,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.MARGIN to QR_QUIET_ZONE_MODULES,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    ),
)

/** Keeps every rendered module an integer number of physical pixels. */
internal fun pairingQrModulePixels(matrix: BitMatrix, targetPixels: Int): Int =
    (targetPixels / matrix.width).coerceAtLeast(1)

internal fun pairingExpiryDescription(expiresAt: String): String =
    "Ссылка действует ещё ${pairingExpiryCountdown(expiresAt)}"

internal fun pairingExpiryCountdown(expiresAt: String, nowMs: Long = System.currentTimeMillis()): String {
    val deadline = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return "ограниченное время"
    val seconds = ((deadline - nowMs).coerceAtLeast(0) + 999) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
