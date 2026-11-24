package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.rest.ApiHttpResponse
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Resolves [ApiQueryParam.dynamicSource] / path-template expressions against the data available
 * while building a request URL. Supported expressions are documented on [ApiQueryParam.dynamicSource].
 */
internal class ImportUrlContext(
    private val account: ApiImportAccount? = null,
    private val ancestorItems: List<JsonObject>? = null,
    private val ancestorVars: Map<String, String> = emptyMap(),
    private val windowStart: String? = null,
    private val windowEnd: String? = null,
) {
    fun resolve(expr: String): String? =
        when {
            expr == "account.id" -> {
                account?.id
            }

            expr.startsWith("account.") -> {
                account?.rawJson?.resolveJsonPath(expr.removePrefix("account."))
            }

            expr == "window.start" -> {
                windowStart
            }

            expr == "window.end" -> {
                windowEnd
            }

            expr == "parent.id" -> {
                ancestorVars.entries.lastOrNull { it.key.endsWith(".id") }?.value
                    ?: ancestorItems?.lastOrNull()?.resolveJsonPath("id")
            }

            expr.startsWith("ancestor[") -> {
                resolveAncestor(expr)
            }

            else -> {
                ancestorVars[expr]
            }
        }

    private fun resolveAncestor(expr: String): String? {
        ancestorVars[expr]?.let { return it }
        val index = expr.substringAfter('[').substringBefore(']').toIntOrNull() ?: return null
        val field = expr.substringAfter("].", missingDelimiterValue = "id")
        return ancestorItems?.getOrNull(index)?.resolveJsonPath(field)
    }
}

/** Substitutes `{expression}` placeholders in [path] using [ctx]; unresolved placeholders are kept. */
internal fun applyPathTemplate(
    path: String,
    ctx: ImportUrlContext,
): String = PATH_TEMPLATE_REGEX.replace(path) { match -> ctx.resolve(match.groupValues[1]) ?: match.value }

/**
 * Inverse of [applyPathTemplate]: matches a concrete [actualPath] against a [template] and returns
 * each placeholder expression mapped to its captured value, or null when the path does not match.
 */
internal fun extractPathVariables(
    template: String,
    actualPath: String,
): Map<String, String>? {
    val exprs = mutableListOf<String>()
    val pattern =
        buildString {
            append('^')
            var last = 0
            for (match in PATH_TEMPLATE_REGEX.findAll(template)) {
                append(Regex.escape(template.substring(last, match.range.first)))
                append("([^/]+)")
                exprs += match.groupValues[1]
                last = match.range.last + 1
            }
            append(Regex.escape(template.substring(last)))
            append('$')
        }
    val match = Regex(pattern).matchEntire(actualPath) ?: return null
    return exprs.mapIndexed { index, expr -> expr to match.groupValues[index + 1] }.toMap()
}

/** Appends static and dynamic [queryParams] to this URL builder, resolving each against [ctx]. */
internal fun URLBuilder.appendQueryParams(
    queryParams: List<ApiQueryParam>,
    ctx: ImportUrlContext,
) {
    for (queryParam in queryParams) {
        val value = queryParam.value ?: queryParam.dynamicSource?.let { ctx.resolve(it) }
        if (value != null) parameters.append(queryParam.name, value)
    }
}

/** Builds a fully-templated URL for [endpoint] (path placeholders + query params) using [ctx]. */
internal fun buildEndpointRequestUrl(
    baseUrl: String,
    endpoint: ApiEndpointConfig,
    ctx: ImportUrlContext,
): String =
    URLBuilder(buildEndpointUrl(baseUrl, applyPathTemplate(endpoint.path, ctx)))
        .apply { appendQueryParams(endpoint.queryParams, ctx) }
        .buildString()

/** Builds the accounts-endpoint URL for the given ancestor [ctx]. */
internal fun buildAccountsUrl(
    strategy: ApiImportStrategy,
    ctx: ImportUrlContext,
): String = buildEndpointRequestUrl(strategy.config.baseUrl, strategy.config.downloadedAccounts().endpoint, ctx)

/**
 * The URL of one page of a bank strategy's transaction feed: the endpoint's templated path and query
 * params, then (for a windowed page) the pagination's templated extra params, then the page's own
 * pagination params. Stored responses are matched back to their request by this URL, so its shape must
 * stay stable.
 */
internal fun buildBankFeedUrl(
    strategy: ApiImportStrategy,
    feed: ApiEndpointConfig,
    ctx: ImportUrlContext,
    page: PageRequest,
): String =
    URLBuilder(buildEndpointUrl(strategy.config.baseUrl, applyPathTemplate(feed.path, ctx)))
        .apply {
            appendQueryParams(feed.queryParams, ctx)
            if (page.window != null) feed.pagination?.let { appendQueryParams(it.extraParams, ctx) }
            page.params.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()

internal data class ApiDateWindow(
    val start: Instant,
    val end: Instant,
)

/**
 * The epoch-ms an incremental download starts at given a [since] watermark: the watermark rolled back
 * by [ApiPaginationConfig.incrementalOverlapDays], so recently-posted or backdated rows are re-fetched.
 *
 * A negative overlap is clamped to zero. Nothing stops a hand-edited or imported config from carrying
 * one, and it would push the start *past* the watermark — skipping records outright — which is the one
 * direction this must never fail in.
 */
internal fun incrementalStartMillis(
    since: Instant,
    pagination: ApiPaginationConfig,
): Long = since.toEpochMilliseconds() - pagination.incrementalOverlapDays.coerceAtLeast(0).toLong() * MILLIS_PER_DAY

/**
 * Produces the date windows to fetch, anchored to fixed epoch boundaries so earlier windows yield
 * stable, cacheable URLs across re-imports; only the final window (ending [now]) shifts.
 *
 * [since] is the incremental watermark — how far an earlier download of the same credential already
 * reached. When set, the sweep starts at that point less [ApiPaginationConfig.incrementalOverlapDays]
 * (never earlier than the full [com.moneymanager.domain.model.apistrategy.ApiDateWindowing.lookbackDays] start, so a watermark can only ever
 * shorten the sweep), which re-fetches the recent tail so rows the provider backdates after a download
 * are still picked up.
 */
internal fun dateWindows(
    pagination: ApiPaginationConfig,
    now: Instant,
    since: Instant? = null,
): List<ApiDateWindow> {
    val windowing = pagination.window ?: return emptyList()
    val nowMillis = now.toEpochMilliseconds()
    val lookbackStart = nowMillis - windowing.lookbackDays.toLong() * MILLIS_PER_DAY
    val rawStart =
        if (since == null) {
            lookbackStart
        } else {
            maxOf(lookbackStart, incrementalStartMillis(since, pagination))
        }
    val windowDays =
        windowing.windowDays
            ?: return if (rawStart < nowMillis) listOf(ApiDateWindow(Instant.fromEpochMilliseconds(rawStart), now)) else emptyList()
    val windowMillis = windowDays.toLong() * MILLIS_PER_DAY
    if (windowMillis <= 0L) return emptyList()
    var start = (rawStart / windowMillis) * windowMillis
    val windows = mutableListOf<ApiDateWindow>()
    while (start < nowMillis) {
        // Providers that cap the startTime/endTime span (Binance: "range too large" at exactly
        // [windowDays]) treat the bound as inclusive, so a full-[windowMillis] span is one millisecond
        // over their limit. Every window but the last ends a millisecond early; consecutive inclusive
        // ranges still tile without a gap. Only a window that actually runs past [now] is clamped back
        // to it - a window landing exactly on [now] still ends a millisecond early (the coverage
        // watermark being 1 ms short is harmless; the incremental overlap re-fetches far more).
        val fullEnd = start + windowMillis
        val end = if (fullEnd > nowMillis) nowMillis else fullEnd - 1
        windows += ApiDateWindow(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end))
        start += windowMillis
    }
    return windows
}

internal fun buildEndpointUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

internal fun ApiResponse.toApiHttpResponse(): ApiHttpResponse =
    ApiHttpResponse(
        statusCode = 200,
        body = json,
        responseId = id.id,
        requestId = requestId.id,
    )

internal fun ApiRequest.encodedPath(): String = runCatching { URLBuilder(url).encodedPath }.getOrNull().orEmpty()

/**
 * Recovers the current account's external id from a stored transaction request. Uses the
 * `account.id` query parameter when the strategy injects the id that way (Monzo); otherwise matches
 * the request path against the transactions-endpoint path template and captures the `{account.id}`
 * segment (Wise). Returns null for requests that are not transaction requests.
 */
internal fun ApiRequest.resolveAccountExternalId(strategy: ApiImportStrategy): String? {
    val accountIdParamName =
        strategy.config
            .bankFeed()
            .endpoint.queryParams
            .firstOrNull { it.dynamicSource == "account.id" }
            ?.name
    if (accountIdParamName != null) {
        return runCatching { URLBuilder(url).parameters[accountIdParamName] }.getOrNull()
    }
    val feed = strategy.config.bankFeed().endpoint
    val path = encodedPath()
    return (sequenceOf(feed.path) + feed.historicalPaths.asSequence()).firstNotNullOfOrNull { template ->
        extractPathVariables(template, path)?.get("account.id")
    }
}

/** True when this request targets the accounts endpoint (path matches the accounts path template). */
internal fun ApiRequest.isAccountsRequest(strategy: ApiImportStrategy): Boolean =
    extractPathVariables(
        strategy.config
            .downloadedAccounts()
            .endpoint.path,
        encodedPath(),
    ) != null &&
        resolveAccountExternalId(strategy) == null

/** Ancestor placeholder values substituted into this accounts request's path (e.g. profile id). */
internal fun ApiRequest.ancestorVars(strategy: ApiImportStrategy): Map<String, String> =
    extractPathVariables(
        strategy.config
            .downloadedAccounts()
            .endpoint.path,
        encodedPath(),
    ).orEmpty()
