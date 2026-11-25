package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.rest.ApiClient
import kotlin.time.Instant

/**
 * A key identifying an endpoint uniquely enough to disambiguate two [ApiDataEndpoint]s that share the
 * same [ApiEndpointConfig.path] but differ only by a static query param baked into the signed body
 * (Kraken's `Ledgers` endpoint fetched once with `type=deposit` and once with `type=withdrawal`, neither
 * of which appears in the request URL since Kraken signs params into the POST body, not the query
 * string). Used both for the incremental-download marker and for matching a stored response back to the
 * data endpoint that produced it.
 */
internal fun endpointDedupeKey(endpoint: ApiEndpointConfig): String {
    val staticParams =
        endpoint.queryParams
            .filter { it.value != null }
            .sortedBy { it.name }
            .joinToString("&") { "${it.name}=${it.value}" }
    return if (staticParams.isEmpty()) endpoint.path else "${endpoint.path}?$staticParams"
}

/**
 * The recorded URL for a page — deliberately built from the plain endpoint path plus out-of-band markers
 * (never the signed URL/query string), so it stays byte-identical across retries of the same logical
 * page regardless of a fresh nonce/signature/timestamp (a signed query string, e.g. Binance's, changes
 * every request; a URL built from it would never resume-skip, nor match back to its endpoint on import).
 * The marker is never sent on the wire (see [ApiClient.send]'s `recordUrl`).
 */
internal fun markerUrl(
    baseUrl: String,
    path: String,
    endpointKey: String,
    window: ApiDateWindow?,
    page: String?,
    fanOutValue: String?,
): String {
    val sb = StringBuilder(buildEndpointUrl(baseUrl, path)).append("?ep=").append(endpointKey)
    window?.let {
        sb
            .append("&ws=")
            .append(it.start.toEpochMilliseconds())
            .append("&we=")
            .append(it.end.toEpochMilliseconds())
    }
    page?.let { sb.append("&pg=").append(it) }
    fanOutValue?.let { sb.append("&fv=").append(it) }
    return sb.toString()
}

/** The start of the date window recorded in an exchange marker URL (`&ws=<epoch ms>`), if it has one. */
internal fun windowStartFromMarker(url: String): Instant? = markerInstant(url, "ws")

/** The end of the date window recorded in an exchange marker URL (`&we=<epoch ms>`), if it has one. */
internal fun windowEndFromMarker(url: String): Instant? = markerInstant(url, "we")

private fun markerInstant(
    url: String,
    param: String,
): Instant? =
    Regex("[?&]$param=(\\d+)")
        .find(url)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.let { Instant.fromEpochMilliseconds(it) }
