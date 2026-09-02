package com.rockmobile.account

import java.time.Instant
import org.json.JSONObject

/** Renews the access token when less than two minutes remain before server-side expiry. */
internal const val ACCESS_TOKEN_REFRESH_LEAD_MS = 2 * 60 * 1000L

internal fun accessTokenNeedsRefresh(expiresAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
    expiresAtMs <= 0L || expiresAtMs - nowMs <= ACCESS_TOKEN_REFRESH_LEAD_MS

internal fun parseAccessExpiresAtMs(body: JSONObject): Long =
    Instant.parse(body.getString("access_expires_at")).toEpochMilli()
