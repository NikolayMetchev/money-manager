@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountBridge
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOrderIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalOrderKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiRequestSigner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

private val logger = logging()

/*
 * Generic download + import for signed exchange strategies — any ApiImportStrategy with a
 * syntheticAccount and dataEndpoints. Entirely config-driven (Crypto.com today; Binance/Kraken later as
 * config), so there is no per-provider code here. The bank-shaped path in ApiSessionImportService is
 * untouched; the two are dispatched by strategy shape.
 */

/** The external-id attribute type name used to match the single exchange account across re-imports. */
private const val EXCHANGE_ACCOUNT_EXTERNAL_ID_ATTR = "exchange-account-external-id"

/** Attribute type storing an exchange transfer's provider id (deposit/withdraw id) for de-duplication. */
private const val EXCHANGE_TXN_ID_ATTR = "exchange.txn_id"

/** External-id attribute keying a blockchain-wallet counterparty account by its address. */
private const val WALLET_ADDRESS_ATTR = "blockchain-wallet-address"

/** Cross-source unique-id attribute holding a transfer's on-chain transaction id. */
private const val BLOCKCHAIN_TXID_ATTR = "blockchain-txid"

/**
 * Downloads every data endpoint of an exchange [strategy] into [sessionId] as signed POST/GET requests,
 * paged by date window. Incremental: a window already stored (recorded URL present) is skipped. The
 * request body carries the api secret/signature and is never persisted (see `ApiClient`).
 */
suspend fun downloadApiSessionExchange(
    apiClient: ApiClient,
    signer: ApiRequestSigner,
    apiKey: String,
    apiSecret: String,
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    // Crypto.com's get-trades / get-order-history are limited to 1 request/second, so pace every request
    // just over 1s to stay clear of rate-limit rejections across all endpoints.
    rateLimitMillis: Long = 1100,
    onProgress: (ApiTransactionsDownloadProgress) -> Unit = {},
): ApiTransactionsDownloadResult {
    val signing = requireNotNull(strategy.requestSigning) { "SIGNED strategy '${strategy.name}' has no requestSigning" }
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId).associateBy { it.requestId }
    val downloadedUrls = existingRequests.filter { existingResponses.containsKey(it.id) }.map { it.url }.toSet()

    var responseCount = 0
    // The nonce must be the CURRENT epoch-ms on every request (exchanges reject a nonce that drifts too
    // far from server time), yet also strictly increasing. Sampling the clock per request and clamping
    // to lastNonce+1 guarantees both across a long multi-endpoint, multi-window download.
    var lastNonce = 0L
    var requestId = 1L
    val now = Clock.System.now()

    // Bodies of previously downloaded requests, keyed by url — lets a resumed offset-paged window (see
    // below) decide whether to keep paging without re-fetching pages it already has.
    val existingResponseJsonByUrl =
        existingRequests.mapNotNull { req -> existingResponses[req.id]?.let { req.url to it.json } }.toMap()

    strategy.dataEndpoints.forEachIndexed { endpointIndex, dataEndpoint ->
        val endpoint = dataEndpoint.endpoint
        val windows = dateWindowsOrSingle(endpoint.pagination, now)
        // A single failing endpoint (e.g. a path this account/product doesn't support) must not abort
        // the whole download; once one window fails, skip the rest of that endpoint's windows.
        var endpointBroken = false
        windows.forEachIndexed { windowIndex, window ->
            if (endpointBroken) return@forEachIndexed
            val offsetParam = endpoint.pagination?.offsetParam
            var offset = 0
            var itemsSeenInWindow = 0
            var keepPaging = true
            while (keepPaging) {
                val params = linkedMapOf<String, String>()
                endpoint.queryParams.forEach { p -> p.value?.let { params[p.name] = it } }
                if (window != null) {
                    val pagination = endpoint.pagination!!
                    params[pagination.startParam] = formatWindowBound(window.start, pagination.windowBoundFormat)
                    params[pagination.endParam] = formatWindowBound(window.end, pagination.windowBoundFormat)
                }
                if (offsetParam != null) params[offsetParam] = offset.toString()

                val endpointUrl = buildExchangeEndpointUrl(strategy.baseUrl, endpoint.path)
                val nonce = nextExchangeNonce(lastNonce, Clock.System.now().toEpochMilliseconds())
                lastNonce = nonce
                val signed =
                    signer.sign(
                        endpointUrl = endpointUrl,
                        path = uriPathOf(endpointUrl),
                        methodName = endpoint.path,
                        params = params,
                        apiKey = apiKey,
                        apiSecret = apiSecret,
                        nonce = nonce,
                        requestId = requestId,
                    )
                requestId += 1

                // Make the recorded URL unique per (endpoint, window, offset) so incremental skip works.
                // For a signed query string (Binance) the signed URL already encodes the window, and
                // appending an unsigned marker would break the signature — so only mark body-based
                // requests (Crypto.com/Kraken).
                val sendUrl =
                    if (signing.bodyFormat == BodyFormat.QUERY_ONLY) {
                        signed.url
                    } else {
                        appendMarker(signed.url, endpointDedupeKey(endpoint), window, offsetParam?.let { offset })
                    }

                onProgress(
                    ApiTransactionsDownloadProgress(
                        accountIndex = endpointIndex + 1,
                        accountCount = strategy.dataEndpoints.size,
                        page = windowIndex + 1,
                        downloadedResponsePageCount = responseCount,
                    ),
                )

                val pageBody: String?
                if (sendUrl !in downloadedUrls) {
                    val method = endpoint.method.name
                    val response = apiClient.send(method, sendUrl, signed.headers, signed.body, signed.contentType)
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
                    if (error != null) {
                        logger.warn { "Skipping endpoint '${endpoint.path}' after error: $error" }
                        endpointBroken = true
                        return@forEachIndexed
                    }
                    responseCount += 1
                    pageBody = response.body
                    if (rateLimitMillis > 0) delay(rateLimitMillis)
                } else {
                    pageBody = existingResponseJsonByUrl[sendUrl]
                }

                keepPaging =
                    if (offsetParam == null) {
                        false
                    } else {
                        val pagination = endpoint.pagination!!
                        val pageItemCount =
                            pageBody
                                ?.let {
                                    responseItemsArray(
                                        it,
                                        endpoint.responseArrayKey,
                                        endpoint.responseObjectValues,
                                        endpoint.itemKeyField,
                                    )
                                }?.size ?: 0
                        itemsSeenInWindow += pageItemCount
                        offset += pagination.limitValue
                        val totalCount = pagination.totalCountField?.let { field -> pageBody?.let { totalCountFromJson(it, field) } }
                        pageItemCount >= pagination.limitValue && (totalCount == null || itemsSeenInWindow < totalCount)
                    }
            }
        }
    }
    return ApiTransactionsDownloadResult(accountCount = strategy.dataEndpoints.size, transactionResponseCount = responseCount)
}

/** A window with epoch bounds; null means a single non-windowed request. */
private fun dateWindowsOrSingle(
    pagination: ApiPaginationConfig?,
    now: Instant,
): List<ApiDateWindow?> =
    if (pagination?.mode == PaginationMode.DATE_WINDOW) {
        dateWindows(pagination, now)
    } else {
        listOf(null)
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

private fun buildExchangeEndpointUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

/**
 * A key identifying an endpoint uniquely enough to disambiguate two [ApiDataEndpoint]s that share the
 * same [ApiEndpointConfig.path] but differ only by a static query param baked into the signed body
 * (Kraken's `Ledgers` endpoint fetched once with `type=deposit` and once with `type=withdrawal`, neither
 * of which appears in the request URL since Kraken signs params into the POST body, not the query
 * string). Used both for the incremental-download marker and for matching a stored response back to the
 * data endpoint that produced it.
 */
private fun endpointDedupeKey(endpoint: ApiEndpointConfig): String {
    val staticParams =
        endpoint.queryParams
            .filter { it.value != null }
            .sortedBy { it.name }
            .joinToString("&") { "${it.name}=${it.value}" }
    return if (staticParams.isEmpty()) endpoint.path else "${endpoint.path}?$staticParams"
}

private fun uriPathOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    val slash = afterScheme.indexOf('/')
    return if (slash >= 0) afterScheme.substring(slash) else "/"
}

private fun appendMarker(
    url: String,
    endpointKey: String,
    window: ApiDateWindow?,
    offset: Int? = null,
): String {
    val sep = if (url.contains('?')) '&' else '?'
    val windowMarker = window?.let { "&ws=${it.start.toEpochMilliseconds()}&we=${it.end.toEpochMilliseconds()}" } ?: ""
    val offsetMarker = offset?.let { "&ofs=$it" } ?: ""
    return "$url${sep}ep=$endpointKey$windowMarker$offsetMarker"
}

/** Formats a date-window bound per [WindowBoundFormat] for a request parameter. */
private fun formatWindowBound(
    instant: Instant,
    format: WindowBoundFormat,
): String =
    when (format) {
        WindowBoundFormat.EPOCH_MS -> instant.toEpochMilliseconds().toString()
        WindowBoundFormat.EPOCH_S -> instant.epochSeconds.toString()
        WindowBoundFormat.ISO_8601 -> instant.toString()
    }

/** Reads an integer total-count field from a response envelope (e.g. Kraken `result.count`). */
private fun totalCountFromJson(
    json: String,
    field: String,
): Int? =
    try {
        (Json.parseToJsonElement(json).resolveJsonPathElement(field) as? JsonPrimitive)?.content?.toIntOrNull()
    } catch (e: SerializationException) {
        null
    }

// -------------------------------------------------------------------------------------------------
// Import: parse stored responses into trades + transfers and hand one ImportBatch to the engine.
// -------------------------------------------------------------------------------------------------

/** Outcome of an exchange import. */
data class ExchangeImportResult(
    val tradesImported: Int,
    val transfersImported: Int,
    val duplicatesSkipped: Int,
    val ordersImported: Int = 0,
    val ordersUpdated: Int = 0,
)

private data class ParsedTrade(
    val id: String,
    val timestamp: Instant,
    val isBuy: Boolean,
    val baseCode: String,
    val quoteCode: String,
    val baseQuantity: BigDecimal,
    val quoteAmount: BigDecimal,
    val feeAmount: BigDecimal?,
    val feeCode: String?,
    val orderId: String?,
    val requestId: ApiRequestId,
    val jsonPath: String,
)

private data class ParsedExchangeTransfer(
    val id: String,
    val timestamp: Instant,
    val currencyCode: String,
    val amount: BigDecimal,
    val direction: TransferDirection,
    val description: String,
    val requestId: ApiRequestId,
    val jsonPath: String,
    /** Blockchain wallet address of the counterparty (blank/null → generic funding account). */
    val counterpartyAddress: String?,
    val network: String?,
    /** On-chain transaction id, used as a cross-source unique identifier. */
    val txid: String?,
    /** Name of an owned account the counterparty aliases to (e.g. "Crypto.com" for an internal deposit). */
    val aliasAccount: String?,
    /** Value looked up against an [ApiDataEndpoint.enrichesTransfers] index (e.g. Kraken Ledgers `refid`). */
    val joinKey: String?,
)

private data class ParsedOrder(
    val orderRef: String,
    val clientOid: String?,
    val side: String,
    val orderType: String?,
    val timeInForce: String?,
    val status: String?,
    val limitPrice: String?,
    val quantity: String?,
    val avgPrice: String?,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val requestId: ApiRequestId,
    val jsonPath: String,
)

/**
 * An enrichment item (e.g. Kraken DepositStatus/WithdrawStatus) supplying on-chain details for a
 * transfer built from another endpoint (e.g. Ledgers), matched by [ApiTransactionMappings.joinKeyField].
 */
private data class ParsedEnrichment(
    val id: String,
    val address: String?,
    val network: String?,
    val txid: String?,
)

/** Accumulator for parsed items across all of a session's responses. */
private class ParsedExchangeData {
    val trades = mutableListOf<ParsedTrade>()
    val transfers = mutableListOf<ParsedExchangeTransfer>()
    val orders = mutableListOf<ParsedOrder>()
    val enrichments = mutableListOf<ParsedEnrichment>()
}

/** Parses one response item into [into] according to its endpoint kind. */
private fun parseExchangeItem(
    obj: JsonObject,
    dataEndpoint: ApiDataEndpoint,
    requestId: ApiRequestId,
    jsonPath: String,
    into: ParsedExchangeData,
) {
    if (dataEndpoint.enrichesTransfers) {
        dataEndpoint.transactionMappings?.let { parseEnrichment(obj, it) }?.let(into.enrichments::add)
        return
    }
    when (dataEndpoint.kind) {
        ApiEndpointKind.TRADES ->
            dataEndpoint.tradeMappings?.let { parseTrade(obj, it, requestId, jsonPath) }?.let(into.trades::add)
        ApiEndpointKind.ORDERS ->
            dataEndpoint.tradeMappings?.let { parseOrder(obj, it, requestId, jsonPath) }?.let(into.orders::add)
        ApiEndpointKind.DEPOSITS ->
            dataEndpoint.transactionMappings
                ?.let { parseExchangeTransfer(obj, it, TransferDirection.IN, requestId, jsonPath) }
                ?.let(into.transfers::add)
        ApiEndpointKind.WITHDRAWALS ->
            dataEndpoint.transactionMappings
                ?.let { parseExchangeTransfer(obj, it, TransferDirection.OUT, requestId, jsonPath) }
                ?.let(into.transfers::add)
        ApiEndpointKind.BANK_TRANSACTIONS -> Unit
    }
}

private fun parseEnrichment(
    obj: JsonObject,
    tm: ApiTransactionMappings,
): ParsedEnrichment? {
    val id = obj.str(tm.idField) ?: return null
    return ParsedEnrichment(
        id = id,
        address = tm.counterpartyAddressField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
        network = tm.counterpartyNetworkField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
        txid = tm.txidField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
    )
}

/**
 * Imports a downloaded exchange session: parses stored responses per data-endpoint kind, auto-creates
 * crypto assets, and builds one [ImportBatch] of cross-asset trades + deposit/withdrawal transfers.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
suspend fun importApiSessionExchange(
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
): ExchangeImportResult {
    val synthetic = requireNotNull(strategy.syntheticAccount) { "Exchange strategy '${strategy.name}' has no syntheticAccount" }
    val source = Source.Api(sessionId)

    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val responses = apiSessionRepository.getResponsesBySession(sessionId)

    val parsed = ParsedExchangeData()
    responses.forEach { response ->
        val request = requestsById[response.requestId] ?: return@forEach
        // Prefer the precise "ep=<key>" marker (disambiguates endpoints sharing a path, e.g. Kraken's
        // Ledgers deposit/withdrawal split); a QUERY_ONLY signed URL (Binance) carries no marker, so fall
        // back to a plain path match, which is unambiguous there since query params live in the URL.
        val dataEndpoint =
            strategy.dataEndpoints.firstOrNull { request.url.contains("ep=${endpointDedupeKey(it.endpoint)}") }
                ?: strategy.dataEndpoints.firstOrNull { request.url.contains(it.endpoint.path) }
                ?: return@forEach
        val items =
            responseItemsArray(
                response.json,
                dataEndpoint.endpoint.responseArrayKey,
                dataEndpoint.endpoint.responseObjectValues,
                dataEndpoint.endpoint.itemKeyField,
            ) ?: return@forEach
        items.forEachIndexed { index, element ->
            (element as? JsonObject)?.let {
                // The real JSON path of this item so the audit view can expand to the exact node.
                val jsonPath = arrayItemJsonPath(dataEndpoint.endpoint.responseArrayKey, index).value
                parseExchangeItem(it, dataEndpoint, response.requestId, jsonPath, parsed)
            }
        }
    }

    // Normalize legacy/provider-specific asset codes (e.g. Kraken "XXBT" -> "BTC") before any
    // currency/crypto lookup, so an aliased code and its canonical form always resolve to one asset.
    val aliases = strategy.assetAliases.mapKeys { it.key.uppercase() }

    fun canonicalAsset(code: String): String = aliases[code.uppercase()] ?: code
    val trades =
        parsed.trades.map {
            it.copy(
                baseCode = canonicalAsset(it.baseCode),
                quoteCode = canonicalAsset(it.quoteCode),
                feeCode = it.feeCode?.let(::canonicalAsset),
            )
        }
    // Enrichment endpoints (e.g. Kraken DepositStatus/WithdrawStatus) supply on-chain address/network/
    // txid for transfers built from a different endpoint (Ledgers), matched by joinKey; a transfer's own
    // fields win when present.
    val enrichByKey = parsed.enrichments.associateBy { it.id }
    val transfers =
        parsed.transfers.map { tx ->
            val enrichment = tx.joinKey?.let { enrichByKey[it] }
            val aliased = tx.copy(currencyCode = canonicalAsset(tx.currencyCode))
            if (enrichment == null) {
                aliased
            } else {
                aliased.copy(
                    counterpartyAddress = aliased.counterpartyAddress ?: enrichment.address,
                    network = aliased.network ?: enrichment.network,
                    txid = aliased.txid ?: enrichment.txid,
                )
            }
        }
    // An order can surface in several download windows (created in one, updated in another); keep the
    // freshest snapshot per order id.
    val orders =
        parsed.orders
            .groupBy { it.orderRef }
            .values
            .map { snapshots -> snapshots.maxBy { it.updatedAt ?: it.createdAt } }

    // Resolve assets: any code that isn't a known fiat currency is treated as a crypto asset and
    // auto-created (the same rule as the CSV importer), sized to the precision the data actually uses.
    val fiatByCode = currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }
    val cryptoCodes = mutableSetOf<String>()

    fun noteCrypto(code: String?) {
        if (code == null || code.uppercase() in fiatByCode) return
        cryptoCodes += code.uppercase()
    }
    for (t in trades) {
        noteCrypto(t.baseCode)
        noteCrypto(t.quoteCode)
        noteCrypto(t.feeCode)
    }
    for (tx in transfers) noteCrypto(tx.currencyCode)

    for (code in cryptoCodes) {
        importEngine.createCrypto(code, name = CryptoRegistry.nameFor(code), source = source)
    }

    val cryptoByCode = cryptoRepository.getAllCryptoAssets().first().associateBy { it.code.uppercase() }

    fun asset(code: String): Asset? = fiatByCode[code.uppercase()] ?: cryptoByCode[code.uppercase()]

    // Resolve attribute types and the accounts (synthetic + fee + funding) in a first batch so the
    // trades/transfers below can reference real account ids (the engine creates trades before accounts).
    val exchangeAcctAttr = importEngine.getOrCreateAttributeType(EXCHANGE_ACCOUNT_EXTERNAL_ID_ATTR)
    val txnIdAttr = importEngine.getOrCreateAttributeType(EXCHANGE_TXN_ID_ATTR)
    val walletAddrAttr = importEngine.getOrCreateAttributeType(WALLET_ADDRESS_ATTR)
    val txidAttr = importEngine.getOrCreateAttributeType(BLOCKCHAIN_TXID_ATTR)

    val syntheticKey = LocalAccountKey("exchange-synthetic")
    val feeKey = LocalAccountKey("exchange-fees")
    val fundingKey = LocalAccountKey("exchange-funding")
    val openingDate =
        (trades.map { it.timestamp } + transfers.map { it.timestamp }).minOrNull() ?: Clock.System.now()
    val accountResult =
        importEngine.import(
            ImportBatch(
                accountsToCreate =
                    listOf(
                        // Match by name so the single Exchange account unifies with the one the CSV export
                        // routes App<->Exchange transfers to (both use "Crypto.com Exchange"), regardless of
                        // which strategy imports first; the external-id attribute is kept for reference.
                        ImportAccountIntent(
                            key = syntheticKey,
                            source = source,
                            match = AccountMatchKey.ByName(synthetic.name),
                            name = synthetic.name,
                            openingDate = openingDate,
                            attributes = listOf(NewAttribute(exchangeAcctAttr, synthetic.externalId)),
                        ),
                        ImportAccountIntent(
                            key = feeKey,
                            source = source,
                            match = AccountMatchKey.ByName("${synthetic.name} Fees"),
                            name = "${synthetic.name} Fees",
                            openingDate = openingDate,
                        ),
                        ImportAccountIntent(
                            key = fundingKey,
                            source = source,
                            match = AccountMatchKey.ByName("${synthetic.name} Funding"),
                            name = "${synthetic.name} Funding",
                            openingDate = openingDate,
                        ),
                    ),
            ),
        )
    val syntheticId = requireNotNull(accountResult.createdAccountIds[syntheticKey])
    val feeAccountId = requireNotNull(accountResult.createdAccountIds[feeKey])
    val fundingId = requireNotNull(accountResult.createdAccountIds[fundingKey])

    val tradeIntents = mutableListOf<ImportTradeIntent>()
    val transferIntents = mutableListOf<ImportTransfer>()
    // Fill-trade keys per order id, collected only for trades that actually became intents (a trade
    // skipped for an unresolvable asset must not surface as a dangling link key).
    val tradeKeysByOrderRef = mutableMapOf<String, MutableList<LocalTradeKey>>()

    trades.forEach forEachTrade@{ t ->
        val baseAsset = asset(t.baseCode)
        val quoteAsset = asset(t.quoteCode)
        if (baseAsset == null || quoteAsset == null) return@forEachTrade
        val baseMoney = Money.fromDisplayValue(t.baseQuantity, baseAsset)
        val quoteMoney = moneyExact(t.quoteAmount, quoteAsset, "Trade ${t.id} quote leg")
        val description = tradeDescription(t)
        // BUY: quote leaves, base arrives. SELL: base leaves, quote arrives. Both on the one account.
        val fromAmount = if (t.isBuy) quoteMoney else baseMoney
        val toAmount = if (t.isBuy) baseMoney else quoteMoney
        val tradeKey = LocalTradeKey("api-$sessionId-trade-${t.id}")
        tradeIntents +=
            ImportTradeIntent(
                key = tradeKey,
                source = Source.Api(sessionId, t.requestId, JsonPath(t.jsonPath)),
                timestamp = t.timestamp,
                description = description,
                fromAccountId = syntheticId,
                fromAmount = fromAmount,
                toAccountId = syntheticId,
                toAmount = toAmount,
            )
        t.orderId?.let { tradeKeysByOrderRef.getOrPut(it, ::mutableListOf).add(tradeKey) }
        // A trade carries no fee slot, so emit any fee as its own movement to the fee account.
        val feeCode = t.feeCode
        val feeAmount = t.feeAmount
        if (feeCode != null && feeAmount != null) {
            val feeAsset = asset(feeCode)
            // A fee whose precision exceeds the asset's scale fails the import (no silent rounding);
            // a genuinely zero fee is dropped.
            val feeMoney = feeAsset?.let { moneyExact(feeAmount, it, "Trade ${t.id} fee") }
            if (feeAsset != null && feeMoney != null && !feeMoney.isZero()) {
                transferIntents +=
                    exchangeTransfer(
                        id = "${t.id}-fee",
                        timestamp = t.timestamp,
                        description = "$description fee",
                        fromAccount = AccountRef.Existing(syntheticId),
                        toAccount = AccountRef.Existing(feeAccountId),
                        money = feeMoney,
                        source = source,
                        requestId = t.requestId,
                        jsonPath = t.jsonPath,
                        txnIdAttr = txnIdAttr,
                    )
            }
        }
    }

    // Counterparty accounts. An aliased counterparty (e.g. an internal deposit whose `address` is
    // "INTERNAL_DEPOSIT") is booked against a named owned account (the Crypto.com App account) so the
    // same movement recorded by the CSV export reconciles to it. Otherwise each distinct blockchain
    // address becomes its own wallet account (keyed by the address); a missing address falls back to the
    // generic funding account.
    val accountKeys = mutableMapOf<String, LocalAccountKey>()
    val counterpartyIntents = mutableListOf<ImportAccountIntent>()
    transfers.mapNotNull { it.aliasAccount }.distinct().forEach { name ->
        val key = LocalAccountKey("alias-$name")
        accountKeys[name] = key
        counterpartyIntents +=
            ImportAccountIntent(key = key, source = source, match = AccountMatchKey.ByName(name), name = name, openingDate = openingDate)
    }
    transfers.filter { it.aliasAccount == null }.mapNotNull { it.counterpartyAddress }.distinct().forEach { address ->
        val key = LocalAccountKey("wallet-$address")
        accountKeys[address] = key
        val network = transfers.firstOrNull { it.counterpartyAddress == address && it.network != null }?.network
        counterpartyIntents +=
            ImportAccountIntent(
                key = key,
                source = source,
                match = AccountMatchKey.ByExternalId(walletAddrAttr, address),
                name = if (network != null) "$network:$address" else address,
                openingDate = openingDate,
                attributes = listOf(NewAttribute(walletAddrAttr, address)),
            )
    }

    val exchangeRef = AccountRef.Existing(syntheticId)
    for (tx in transfers) {
        val txAsset = asset(tx.currencyCode) ?: continue // single jump — allowed
        val money = Money.fromDisplayValue(tx.amount, txAsset)
        val counterpartyKey = tx.aliasAccount?.let { accountKeys[it] } ?: tx.counterpartyAddress?.let { accountKeys[it] }
        val counterparty = counterpartyKey?.let { AccountRef.Local(it) } ?: AccountRef.Existing(fundingId)
        val (from, to) = if (tx.direction == TransferDirection.IN) counterparty to exchangeRef else exchangeRef to counterparty
        transferIntents +=
            exchangeTransfer(
                id = tx.id,
                timestamp = tx.timestamp,
                description = tx.description,
                fromAccount = from,
                toAccount = to,
                money = money,
                source = source,
                requestId = tx.requestId,
                jsonPath = tx.jsonPath,
                txnIdAttr = txnIdAttr,
                txid = tx.txid,
                txidAttr = txidAttr,
            )
    }

    // Internal-transfer reconciliation: bridge the exchange account to any configured app account
    // (e.g. the CSV "Crypto.com" App account) so App<->Exchange transfers collapse into one movement.
    val reconcile = strategy.internalTransferReconcile
    val existingAccounts = accountRepository.getAllAccounts().first()
    val bridges =
        reconcile
            ?.bridges
            ?.mapNotNull { bridge ->
                existingAccounts
                    .firstOrNull { it.name == bridge.otherAccountName }
                    ?.let { AccountBridge(exchangeAccountId = syntheticId, appAccountId = it.id) }
            }.orEmpty()

    val orderIntents =
        orders.map { o ->
            ImportOrderIntent(
                key = LocalOrderKey("api-$sessionId-order-${o.orderRef}"),
                source = Source.Api(sessionId, o.requestId, JsonPath(o.jsonPath)),
                accountId = syntheticId,
                orderRef = o.orderRef,
                clientOid = o.clientOid,
                side = o.side,
                orderType = o.orderType,
                timeInForce = o.timeInForce,
                status = o.status,
                limitPrice = o.limitPrice,
                quantity = o.quantity,
                avgPrice = o.avgPrice,
                createdAt = o.createdAt,
                updatedAt = o.updatedAt,
                tradeKeys = tradeKeysByOrderRef[o.orderRef].orEmpty(),
            )
        }

    val result =
        importEngine.import(
            ImportBatch(
                transfers = transferIntents,
                trades = tradeIntents,
                orders = orderIntents,
                accountsToCreate = counterpartyIntents,
                dedupePolicy =
                    DedupePolicy.ApiMultiKey(
                        // A configured reconcile window also enables plain cross-source reconciliation, so
                        // an aliased internal transfer (booked directly against the App account) links to
                        // the identical leg the CSV export produces, regardless of which imports first.
                        reconcileWindow = reconcile?.windowSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
                        reconciledExclusionAttributeTypeId =
                            if (reconcile == null) null else AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID),
                        reconciledRelationshipTypeId =
                            if (reconcile == null) null else RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID),
                        internalTransferBridges = bridges,
                        internalTransferWindow = reconcile?.windowSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
                        internalTransferAmountTolerance =
                            reconcile?.amountTolerancePercent?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO,
                    ),
                apiIdExtractor =
                    ExistingApiIdExtractor { transfer ->
                        transfer.attributes.firstOrNull { it.attributeType.id == txnIdAttr }?.value
                    },
            ),
        )
    return ExchangeImportResult(
        tradesImported = tradeIntents.size,
        transfersImported = result.transfersImported,
        duplicatesSkipped = result.duplicates,
        ordersImported = orderIntents.size - result.updatedOrderKeys.size - result.dedupedOrderKeys.size,
        ordersUpdated = result.updatedOrderKeys.size,
    )
}

private fun exchangeTransfer(
    id: String,
    timestamp: Instant,
    description: String,
    fromAccount: AccountRef,
    toAccount: AccountRef,
    money: Money,
    source: Source,
    requestId: ApiRequestId,
    jsonPath: String,
    txnIdAttr: AttributeTypeId,
    txid: String? = null,
    txidAttr: AttributeTypeId? = null,
): ImportTransfer {
    val txidAttribute = if (txid != null && txidAttr != null) NewAttribute(txidAttr, txid) else null
    return ImportTransfer(
        source = source,
        rowKey = ImportRowKey.ApiJsonPath(requestId, jsonPath),
        fromAccount = fromAccount,
        toAccount = toAccount,
        timestamp = timestamp,
        description = description,
        amount = money,
        // The exchange's own id keys re-imports (skip). The on-chain txid is stored as an attribute for
        // cross-source reconciliation — NOT as a dedupe uniqueKey, which would skip (rather than link) an
        // opposite leg that shares the same txid.
        apiId = id,
        attributes = listOfNotNull(NewAttribute(txnIdAttr, id), txidAttribute),
    )
}

// No order-derived suffix: existing databases were imported with plain "<verb> BASE/QUOTE"
// descriptions, and trade dedupe is a full-tuple match including description — changing this format
// would duplicate every trade on re-import. Order type/status live on the linked exchange_order.
private fun tradeDescription(trade: ParsedTrade): String {
    val verb = if (trade.isBuy) "Buy" else "Sell"
    return "$verb ${trade.baseCode}/${trade.quoteCode}"
}

private fun parseOrder(
    obj: JsonObject,
    tm: ApiTradeMappings,
    requestId: ApiRequestId,
    jsonPath: String,
): ParsedOrder? {
    val orderRef = (tm.orderIdField?.let { obj.str(it) } ?: obj.str(tm.idField)) ?: return null
    val side = obj.str(tm.sideField) ?: return null
    val createdAt = obj.str(tm.timestampField)?.let { parseApiTimestamp(it, tm.timestampFormat) } ?: return null
    return ParsedOrder(
        orderRef = orderRef,
        clientOid = tm.clientOidField?.let { obj.str(it) },
        side = side,
        orderType = tm.orderTypeField?.let { obj.str(it) },
        timeInForce = tm.timeInForceField?.let { obj.str(it) },
        status = tm.orderStatusField?.let { obj.str(it) },
        limitPrice = tm.limitPriceField?.let { obj.str(it) },
        quantity = obj.str(tm.baseQuantityField),
        avgPrice = tm.avgPriceField?.let { obj.str(it) },
        createdAt = createdAt,
        updatedAt = tm.updateTimestampField?.let { obj.str(it) }?.let { parseApiTimestamp(it, tm.timestampFormat) },
        requestId = requestId,
        jsonPath = jsonPath,
    )
}

private fun parseTrade(
    obj: JsonObject,
    tm: ApiTradeMappings,
    requestId: ApiRequestId,
    jsonPath: String,
): ParsedTrade? {
    val (baseCode, quoteCode) =
        if (tm.splitMode == InstrumentSplitMode.EXPLICIT_FIELDS) {
            val base = tm.baseAssetField?.let { obj.str(it) }
            val quote = tm.quoteAssetField?.let { obj.str(it) }
            if (base != null && quote != null) base to quote else return null
        } else {
            val instrument = obj.str(tm.instrumentField) ?: return null
            splitInstrument(instrument, tm) ?: return null
        }
    val side = obj.str(tm.sideField) ?: return null
    val isBuy = side in tm.buyValues
    val baseQty = obj.str(tm.baseQuantityField)?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: return null
    val quoteAmount =
        tm.quoteQuantityField?.let { obj.str(it)?.let { v -> runCatching { BigDecimal(v) }.getOrNull() } }
            ?: tm.priceField?.let { obj.str(it)?.let { p -> runCatching { baseQty * BigDecimal(p) }.getOrNull() } }
            ?: return null
    val timestamp = obj.str(tm.timestampField)?.let { parseApiTimestamp(it, tm.timestampFormat) } ?: return null
    val id = obj.str(tm.idField) ?: return null
    return ParsedTrade(
        id = id,
        timestamp = timestamp,
        isBuy = isBuy,
        baseCode = baseCode,
        quoteCode = quoteCode,
        baseQuantity = baseQty.abs(),
        quoteAmount = quoteAmount.abs(),
        feeAmount = tm.feeField?.let { obj.str(it)?.let { v -> runCatching { BigDecimal(v).abs() }.getOrNull() } },
        // Most exchanges charge the trade fee in the quote asset without a separate field for it
        // (Kraken); an explicit feeCurrencyField (Crypto.com) overrides this default.
        feeCode = tm.feeCurrencyField?.let { obj.str(it) } ?: quoteCode,
        orderId = tm.orderIdField?.let { obj.str(it) },
        requestId = requestId,
        jsonPath = jsonPath,
    )
}

private fun splitInstrument(
    instrument: String,
    tm: ApiTradeMappings,
): Pair<String, String>? =
    when (tm.splitMode) {
        InstrumentSplitMode.SEPARATOR -> {
            val parts = instrument.split(tm.instrumentSeparator)
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
        // EXPLICIT_FIELDS is resolved directly in parseTrade from the base/quote asset fields.
        InstrumentSplitMode.EXPLICIT_FIELDS -> null
        InstrumentSplitMode.QUOTE_SUFFIX -> {
            val quote = tm.quoteAssets.filter { instrument.endsWith(it) }.maxByOrNull { it.length }
            quote?.let { instrument.removeSuffix(it) to it }
        }
    }

private fun parseExchangeTransfer(
    obj: JsonObject,
    tm: ApiTransactionMappings,
    direction: TransferDirection,
    requestId: ApiRequestId,
    jsonPath: String,
): ParsedExchangeTransfer? {
    val amount = obj.str(tm.amountField)?.let { runCatching { BigDecimal(it).abs() }.getOrNull() } ?: return null
    val currency = obj.str(tm.currencyField) ?: return null
    val timestamp = obj.str(tm.timestampField)?.let { parseApiTimestamp(it, tm.timestampFormat) } ?: return null
    val id = obj.str(tm.idField) ?: return null
    val description =
        obj.str(tm.descriptionField)?.takeIf { it.isNotBlank() }
            ?: if (direction == TransferDirection.IN) "Deposit $currency" else "Withdraw $currency"
    return ParsedExchangeTransfer(
        id = id,
        timestamp = timestamp,
        currencyCode = currency,
        amount = amount,
        direction = direction,
        description = description,
        requestId = requestId,
        jsonPath = jsonPath,
        counterpartyAddress = tm.counterpartyAddressField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
        network = tm.counterpartyNetworkField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
        txid = tm.txidField?.let { obj.str(it) }?.takeIf { it.isNotBlank() },
        aliasAccount = tm.counterpartyAliasField?.let { obj.str(it) }?.let { tm.counterpartyAccountAliases[it] },
        joinKey = tm.joinKeyField?.let { obj.str(it) },
    )
}

/** Converts an exchange-reported display value exactly; throws if the asset's scale can't
 * represent it, so precision is never silently lost. */
private fun moneyExact(
    displayValue: BigDecimal,
    asset: Asset,
    context: String,
): Money =
    try {
        Money.fromDisplayValue(displayValue.abs(), asset)
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException(
            "$context: $displayValue ${asset.code} cannot be represented exactly at scale ${asset.scaleFactor}",
            e,
        )
    }

private fun JsonObject.str(path: String): String? =
    (resolveJsonPathElement(path) as? kotlinx.serialization.json.JsonPrimitive)?.let { it.contentOrNullCompat() }

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
