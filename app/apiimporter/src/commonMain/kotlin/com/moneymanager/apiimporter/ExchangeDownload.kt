package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.excludedAttributeValue
import com.moneymanager.domain.model.rules.ownedAttributeValues
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.EXCHANGE_TXN_ID_ATTR
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOrderIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalOrderKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.internalTransferBridges
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiRequestSigner
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val logger = Logger.withTag("ExchangeDownload")

/** Upper bound on exponential rate-limit backoff, so a misconfigured base delay can't stall for hours. */
private const val MAX_RATE_LIMIT_BACKOFF_MILLIS = 60_000L

/**
 * Downloads every data endpoint of an exchange [strategy] into [sessionId] as signed POST/GET requests,
 * paged by date window (or, for [ApiEndpointConfig.fanOut], once per resolved value). The request body
 * carries the api secret/signature and is never persisted (see `ApiClient`).
 *
 * Runs in two passes: every non-fan-out data endpoint first (so [ApiValueSet.FromDataEndpoint] has
 * something to read), then every fan-out endpoint, each expanded per [ApiEndpointConfig.fanOut] and
 * swept once per surviving value. `ApiStrategyConfig.valueEndpoints` are fetched before either pass.
 *
 * Incremental on two levels: a page already stored in *this* session (recorded URL present) is skipped,
 * which lets an interrupted download resume; and [watermarks] — how far earlier sessions of the same
 * credential already reached, keyed by [endpointDedupeKey] (fan-out endpoints: keyed additionally by
 * value) — moves each endpoint's sweep start forward so a routine download fetches only the recent tail.
 * Pass empty [watermarks] (or [forceFullDownload]) to re-sweep the whole configured lookback from
 * scratch. [com.moneymanager.domain.model.apistrategy.ApiPaging.ForwardId] endpoints ignore the watermark entirely; see its KDoc.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
suspend fun downloadApiSessionExchange(
    apiClient: ApiClient,
    signer: ApiRequestSigner,
    apiKey: String,
    apiSecret: String,
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    watermarks: Map<String, Instant> = emptyMap(),
    forceFullDownload: Boolean = false,
    // Crypto.com's get-trades / get-order-history are limited to 1 request/second, so pace every request
    // just over 1s to stay clear of rate-limit rejections across all endpoints.
    rateLimitMillis: Long = 1100,
    onProgress: (Progress) -> Unit = {},
): ApiTransactionsDownloadResult {
    requireNotNull(strategy.config.requestSigning) { "SIGNED strategy '${strategy.name}' has no requestSigning" }
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId).associateBy { it.requestId }
    val downloadedUrls = existingRequests.filter { existingResponses.containsKey(it.id) }.map { it.url }.toSet()
    val existingResponseJsonByUrl =
        existingRequests.mapNotNull { req -> existingResponses[req.id]?.let { req.url to it.json } }.toMap()

    var responseCount = 0
    // Set by [fetchPage] when a request failed with a [ApiPaginationConfig.windowRangeErrorSubstrings]
    // match: the provider won't serve that date window (too old, or the span is wider than it allows),
    // but the other windows are fine, so the caller skips just this one instead of dropping the endpoint.
    var lastFetchWindowOutOfRange = false
    // The nonce must be the CURRENT epoch-ms on every request (exchanges reject a nonce that drifts too
    // far from server time), yet also strictly increasing. Sampling the clock per request and clamping
    // to lastNonce+1 guarantees both across a long multi-endpoint, multi-window download.
    var lastNonce = 0L
    var requestId = 1L
    // Difference between the provider's clock and this machine's, measured once on first use. A signed
    // request carries a nonce the provider checks against its OWN clock, and an NTP-synced machine can
    // still sit a second or more from it; left uncorrected that gap is spent before the request is even
    // sent, and a locally-fast clock cannot be compensated by widening the provider's tolerance at all.
    // Null until measured; a failed probe records 0 so it is attempted only once per download.
    var measuredClockOffsetMillis: Long? = null

    suspend fun serverClockOffsetMillis(): Long {
        measuredClockOffsetMillis?.let { return it }
        val sync = strategy.config.requestSigning?.serverTimeSync
        val offset =
            if (sync == null) {
                0L
            } else {
                runCatchingCancellable {
                    val url = buildEndpointUrl(strategy.config.baseUrl, sync.path)
                    val body = apiClient.send(method = "GET", url = url, storeResponse = false).body
                    val serverMillis =
                        requireNotNull(
                            Json
                                .parseToJsonElement(body)
                                .jsonObject
                                .resolveJsonPath(sync.field)
                                ?.toLongOrNull(),
                        ) { "no numeric '${sync.field}' in the ${strategy.name} time response" }
                    (serverMillis - Clock.System.now().toEpochMilliseconds()).also {
                        logger.i { "Clock offset against '${strategy.name}': ${it}ms" }
                    }
                }.getOrElse {
                    // The provider's own clock is unreachable; fall back to the local one rather than
                    // failing the whole download, and say so, since it is a likely cause if signing fails.
                    logger.w { "Could not read '${strategy.name}' server time; signing with the local clock: ${it.message}" }
                    0L
                }
            }
        measuredClockOffsetMillis = offset
        return offset
    }

    val now = Clock.System.now()
    var earliestIncrementalStart: Instant? = null
    val endpointCount = strategy.config.dataEndpoints.size

    // Items already downloaded THIS call, keyed by data-endpoint path — feeds ApiValueSet.FromDataEndpoint
    // for a fan-out resolved once every non-fan-out endpoint has run (see the two-pass split below).
    val dataItemsByPath = mutableMapOf<String, MutableList<JsonObject>>()
    val valueItemsByPath = mutableMapOf<String, List<JsonObject>>()

    /** One HTTP attempt (signed or [ApiEndpointConfig.unsigned]) with rate-limit retry/backoff. */
    suspend fun fetchPage(
        endpoint: ApiEndpointConfig,
        params: LinkedHashMap<String, String>,
        recordedUrl: String,
        fanOutValue: String? = null,
    ): String? {
        if (recordedUrl in downloadedUrls) return existingResponseJsonByUrl[recordedUrl]
        lastFetchWindowOutOfRange = false
        var rateLimitRetries = 0
        while (true) {
            val endpointUrl = buildEndpointUrl(strategy.config.baseUrl, resolveFanOutPath(endpoint.path, fanOutValue))
            val response =
                if (endpoint.unsigned) {
                    apiClient.send(
                        method = endpoint.method.name,
                        url = appendUnsignedQueryParams(endpointUrl, params),
                        recordUrl = recordedUrl,
                        storeResponse = endpoint.storeResponse,
                    )
                } else {
                    val nowMillis = Clock.System.now().toEpochMilliseconds() + serverClockOffsetMillis()
                    val nonce = nextExchangeNonce(lastNonce, nowMillis)
                    lastNonce = nonce
                    // Parameters the signing scheme adds to every signed request (Binance's recvWindow).
                    // They go in with the endpoint's own params so the signature covers them.
                    val signedParams =
                        strategy.config.requestSigning
                            ?.signedParams
                            .orEmpty()
                    val paramsToSign =
                        if (signedParams.isEmpty()) {
                            params
                        } else {
                            LinkedHashMap(params).apply {
                                signedParams.forEach { p -> p.value?.let { value -> getOrPut(p.name) { value } } }
                            }
                        }
                    val signed =
                        signer.sign(
                            endpointUrl = endpointUrl,
                            path = uriPathOf(endpointUrl),
                            methodName = endpoint.path,
                            params = paramsToSign,
                            apiKey = apiKey,
                            apiSecret = apiSecret,
                            nonce = nonce,
                            requestId = requestId,
                            httpMethod = endpoint.method.name,
                            nowEpochSeconds = nowMillis / 1_000,
                        )
                    requestId += 1
                    apiClient.send(
                        method = endpoint.method.name,
                        url = signed.url,
                        headers = signed.headers,
                        body = signed.body,
                        contentType = signed.contentType,
                        recordUrl = recordedUrl,
                        storeResponse = endpoint.storeResponse,
                    )
                }
            val error =
                when {
                    response.statusCode != 200 -> "HTTP ${response.statusCode}: ${response.body}"

                    !responseCodeOk(
                        response.body,
                        endpoint.successCodeField,
                        endpoint.successCodeOkValue,
                        endpoint.errorArrayField,
                    ) -> "API error: ${response.body}"

                    else -> null
                }
            if (error == null) {
                responseCount += 1
                val effectiveDelayMillis = (strategy.config.rateLimitMillis ?: rateLimitMillis) * endpoint.requestCostWeight
                if (effectiveDelayMillis > 0) delay(effectiveDelayMillis.milliseconds)
                return response.body
            }
            val isRateLimited = strategy.config.rateLimitErrorSubstrings.any { error.contains(it, ignoreCase = true) }
            if (!isRateLimited || rateLimitRetries >= strategy.config.maxRateLimitRetries) {
                val windowOutOfRange =
                    endpoint.pagination
                        ?.window
                        ?.rangeErrorSubstrings
                        ?.any { error.contains(it, ignoreCase = true) } == true
                if (windowOutOfRange) {
                    lastFetchWindowOutOfRange = true
                    logger.w { "Skipping one out-of-range date window of '${endpoint.path}': $error" }
                } else {
                    logger.w { "Skipping endpoint '${endpoint.path}' after error: $error" }
                }
                return null
            }
            rateLimitRetries += 1
            val backoffMillis =
                (strategy.config.rateLimitBackoffMillis * (1L shl (rateLimitRetries - 1))).coerceAtMost(MAX_RATE_LIMIT_BACKOFF_MILLIS)
            logger.w {
                "Rate-limited on '${endpoint.path}' " +
                    "(attempt $rateLimitRetries/${strategy.config.maxRateLimitRetries}); retrying in ${backoffMillis}ms"
            }
            delay(backoffMillis.milliseconds)
        }
    }

    fun pageItems(
        endpoint: ApiEndpointConfig,
        body: String?,
    ): List<JsonObject> =
        body
            ?.let {
                responseItemsArray(
                    it,
                    endpoint.responseArrayKey,
                    endpoint.responseObjectValues,
                    endpoint.itemKeyField,
                    endpoint.nestedItemsKey,
                )
            }?.filterIsInstance<JsonObject>()
            .orEmpty()

    /** The endpoint's own params (+ a fan-out value), then the page's pagination params. */
    fun requestParams(
        endpoint: ApiEndpointConfig,
        page: PageRequest,
        fanOutParam: String?,
        fanOutValue: String?,
    ): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
        endpoint.queryParams.forEach { p -> p.value?.let { params[p.name] = it } }
        if (fanOutParam != null && fanOutValue != null) params[fanOutParam] = fanOutValue
        params.putAll(page.params)
        return params
    }

    /** Downloads one endpoint's whole sweep for a single fan-out value ([fanOutParam]/[fanOutValue] null for none). */
    suspend fun sweepEndpoint(
        endpoint: ApiEndpointConfig,
        endpointIndex: Int,
        fanOutParam: String?,
        fanOutValue: String?,
        itemSink: MutableList<JsonObject>?,
    ) {
        val endpointKey = endpointDedupeKey(endpoint)
        val coverageKey = fanOutValue?.let { "$endpointKey|fv=$it" } ?: endpointKey
        val walk =
            walkPages(
                pagination = endpoint.pagination,
                now = now,
                since = if (forceFullDownload) null else watermarks[coverageKey],
                fetch = { page ->
                    onProgress(
                        apiDownloadProgress(
                            accountIndex = endpointIndex + 1,
                            accountCount = endpointCount,
                            page = page.windowIndex + 1,
                            downloadedResponsePageCount = responseCount,
                        ),
                    )
                    val recordedUrl =
                        markerUrl(strategy.config.baseUrl, endpoint.path, endpointKey, page.window, page.pageMarker, fanOutValue)
                    val body = fetchPage(endpoint, requestParams(endpoint, page, fanOutParam, fanOutValue), recordedUrl, fanOutValue)
                    when {
                        body != null -> PageOutcome.Fetched(body, pageItems(endpoint, body).also { itemSink?.addAll(it) })

                        // A window the provider won't serve is skipped (no coverage recorded for it) so the
                        // newer windows still run; any other failure abandons the endpoint.
                        lastFetchWindowOutOfRange -> PageOutcome.WindowOutOfRange

                        else -> PageOutcome.Failed
                    }
                },
                onUnitComplete = { coveredUntil -> importEngine.recordApiDownloadCoverage(sessionId, coverageKey, coveredUntil) },
            )
        walk.incrementalStart?.let { earliestIncrementalStart = minOf(it, earliestIncrementalStart ?: it) }
    }

    // Value endpoints: fetched once each (no windowing - a snapshot, e.g. balances/symbol universe),
    // optionally paged, before either data-endpoint pass so fan-out resolution can read them. A failed
    // page keeps whatever the earlier pages returned.
    strategy.config.valueEndpoints.forEach { endpoint ->
        val endpointKey = endpointDedupeKey(endpoint)
        val items = mutableListOf<JsonObject>()
        walkPages(
            pagination = endpoint.pagination,
            now = now,
            since = null,
            windowed = false,
            fetch = { page ->
                val recordedUrl = markerUrl(strategy.config.baseUrl, endpoint.path, endpointKey, null, page.pageMarker, null)
                val body = fetchPage(endpoint, requestParams(endpoint, page, null, null), recordedUrl)
                if (body == null) PageOutcome.Failed else PageOutcome.Fetched(body, pageItems(endpoint, body).also { items += it })
            },
        )
        valueItemsByPath[endpointKey] = items
    }

    // Pass 1: every non-fan-out data endpoint, so fan-out endpoints (pass 2) can read what they fetched.
    strategy.config.dataEndpoints.forEachIndexed { endpointIndex, dataEndpoint ->
        if (dataEndpoint.endpoint.fanOut != null) return@forEachIndexed
        val sink = mutableListOf<JsonObject>()
        sweepEndpoint(dataEndpoint.endpoint, endpointIndex, null, null, sink)
        dataItemsByPath[endpointDedupeKey(dataEndpoint.endpoint)] = sink
    }

    // Pass 2: every fan-out data endpoint, once per resolved value.
    strategy.config.dataEndpoints.forEachIndexed { endpointIndex, dataEndpoint ->
        val fanOut = dataEndpoint.endpoint.fanOut ?: return@forEachIndexed
        val values = resolveValueSet(fanOut.values, valueItemsByPath, dataItemsByPath, fanOut.preserveCase)
        val allowed = fanOut.validAgainst?.let { resolveValueSet(it, valueItemsByPath, dataItemsByPath, fanOut.preserveCase).toSet() }
        val surviving = (if (allowed != null) values.filter { it in allowed } else values).distinct().sorted()
        surviving.forEach { value -> sweepEndpoint(dataEndpoint.endpoint, endpointIndex, fanOut.param, value, null) }
    }

    return ApiTransactionsDownloadResult(
        accountCount = endpointCount,
        transactionResponseCount = responseCount,
        incrementalSince = earliestIncrementalStart,
    )
}

/**
 * Resolves an [ApiValueSet] to its concrete string values, given the value-endpoint and (non-fan-out)
 * data-endpoint items already downloaded this session (see [ApiValueSet]'s KDoc for the vocabulary).
 * Blank values are dropped and, unless [preserveCase], every value is uppercased, matching how
 * asset/symbol codes are conventionally cased by the exchanges this engine targets.
 */
internal fun resolveValueSet(
    valueSet: ApiValueSet,
    valueItemsByPath: Map<String, List<JsonObject>>,
    dataItemsByPath: Map<String, List<JsonObject>>,
    preserveCase: Boolean = false,
): List<String> =
    when (valueSet) {
        is ApiValueSet.Static -> {
            valueSet.values
        }

        is ApiValueSet.FromValueEndpoint -> {
            valueItemsByPath[valueSet.endpointPath].orEmpty().flatMap { item ->
                valueSet.fields.mapNotNull { field -> item.resolveJsonPath(field) }
            }
        }

        is ApiValueSet.FromDataEndpoint -> {
            dataItemsByPath[valueSet.endpointPath].orEmpty().flatMap { item ->
                valueSet.fields.mapNotNull { field -> item.resolveJsonPath(field) }
            }
        }

        is ApiValueSet.Union -> {
            valueSet.sets.flatMap { resolveValueSet(it, valueItemsByPath, dataItemsByPath, preserveCase) }
        }

        is ApiValueSet.CrossProduct -> {
            val lefts = resolveValueSet(valueSet.left, valueItemsByPath, dataItemsByPath, preserveCase).distinct()
            val rights = resolveValueSet(valueSet.right, valueItemsByPath, dataItemsByPath, preserveCase).distinct()
            lefts.flatMap { l -> rights.map { r -> valueSet.template.replace("{left}", l).replace("{right}", r) } }
        }
    }.filter { it.isNotBlank() }.map { if (preserveCase) it else it.uppercase() }

/** The `{fanOut}` placeholder an [ApiEndpointConfig.path] may carry, replaced by each fan-out value. */
private const val FAN_OUT_PATH_PLACEHOLDER = "{fanOut}"

/** [path] with its `{fanOut}` placeholder (if any) replaced by the URL-path-encoded [fanOutValue]. */
internal fun resolveFanOutPath(
    path: String,
    fanOutValue: String?,
): String = if (fanOutValue == null) path else path.replace(FAN_OUT_PATH_PLACEHOLDER, fanOutValue.encodeURLPathPart())

/** Appends [params] as an unsigned, percent-encoded query string — used for [ApiEndpointConfig.unsigned]. */
private fun appendUnsignedQueryParams(
    baseUrl: String,
    params: Map<String, String>,
): String {
    if (params.isEmpty()) return baseUrl
    val query = params.entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
    return "$baseUrl?$query"
}

/**
 * The next request nonce: the current epoch-ms, but never less than the previous nonce + 1. Exchanges
 * require the nonce to track server time yet be strictly increasing; this satisfies both even when two
 * requests fall in the same millisecond.
 */
internal fun nextExchangeNonce(
    lastNonce: Long,
    nowMillis: Long,
): Long = maxOf(nowMillis, lastNonce + 1)

private fun uriPathOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    val slash = afterScheme.indexOf('/')
    return if (slash >= 0) afterScheme.substring(slash) else "/"
}

/** Reads a string field from a response envelope (e.g. a next-page token), null if absent or not a primitive. */
internal fun stringFieldFromJson(
    json: String,
    field: String,
): String? =
    try {
        (Json.parseToJsonElement(json).resolveJsonPathElement(field) as? JsonPrimitive)?.contentOrNull
    } catch (e: SerializationException) {
        null
    }

/** Reads an integer total-count field from a response envelope (e.g. Kraken `result.count`). */
internal fun totalCountFromJson(
    json: String,
    field: String,
): Int? =
    try {
        (Json.parseToJsonElement(json).resolveJsonPathElement(field) as? JsonPrimitive)?.content?.toIntOrNull()
    } catch (e: SerializationException) {
        null
    }
