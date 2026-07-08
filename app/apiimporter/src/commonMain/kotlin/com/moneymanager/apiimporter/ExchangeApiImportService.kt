@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.TransferDirection
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
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiRequestSigner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

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

    strategy.dataEndpoints.forEachIndexed { endpointIndex, dataEndpoint ->
        val endpoint = dataEndpoint.endpoint
        val windows = dateWindowsOrSingle(endpoint.pagination, now)
        windows.forEachIndexed { windowIndex, window ->
            val params = linkedMapOf<String, String>()
            endpoint.queryParams.forEach { p -> p.value?.let { params[p.name] = it } }
            if (window != null) {
                val pagination = endpoint.pagination!!
                params[pagination.startParam] = window.start.toEpochMilliseconds().toString()
                params[pagination.endParam] = window.end.toEpochMilliseconds().toString()
            }

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

            // Make the recorded URL unique per (endpoint, window) so incremental skip works. For a signed
            // query string (Binance) the signed URL already encodes the window, and appending an unsigned
            // marker would break the signature — so only mark body-based requests (Crypto.com/Kraken).
            val sendUrl =
                if (signing.bodyFormat == BodyFormat.QUERY_ONLY) {
                    signed.url
                } else {
                    appendMarker(signed.url, endpoint.path, window)
                }

            onProgress(
                ApiTransactionsDownloadProgress(
                    accountIndex = endpointIndex + 1,
                    accountCount = strategy.dataEndpoints.size,
                    page = windowIndex + 1,
                    downloadedResponsePageCount = responseCount,
                ),
            )

            if (sendUrl !in downloadedUrls) {
                val method = endpoint.method.name
                val response = apiClient.send(method, sendUrl, signed.headers, signed.body, signed.contentType)
                if (response.statusCode != 200) {
                    throw ApiSessionImportException("HTTP ${response.statusCode}: ${response.body}")
                }
                if (!responseCodeOk(response.body, endpoint.successCodeField, endpoint.successCodeOkValue)) {
                    throw ApiSessionImportException("API error for ${endpoint.path}: ${response.body}")
                }
                responseCount += 1
                if (rateLimitMillis > 0) delay(rateLimitMillis)
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

private fun uriPathOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    val slash = afterScheme.indexOf('/')
    return if (slash >= 0) afterScheme.substring(slash) else "/"
}

private fun appendMarker(
    url: String,
    endpointPath: String,
    window: ApiDateWindow?,
): String {
    val sep = if (url.contains('?')) '&' else '?'
    val windowMarker = window?.let { "&ws=${it.start.toEpochMilliseconds()}&we=${it.end.toEpochMilliseconds()}" } ?: ""
    return "$url${sep}ep=$endpointPath$windowMarker"
}

// -------------------------------------------------------------------------------------------------
// Import: parse stored responses into trades + transfers and hand one ImportBatch to the engine.
// -------------------------------------------------------------------------------------------------

/** Outcome of an exchange import. */
data class ExchangeImportResult(
    val tradesImported: Int,
    val transfersImported: Int,
    val duplicatesSkipped: Int,
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
)

private data class ParsedExchangeTransfer(
    val id: String,
    val timestamp: Instant,
    val currencyCode: String,
    val amount: BigDecimal,
    val direction: TransferDirection,
    val description: String,
    val requestId: ApiRequestId,
)

private data class OrderMeta(
    val type: String?,
    val status: String?,
)

/** Accumulator for parsed items across all of a session's responses. */
private class ParsedExchangeData {
    val trades = mutableListOf<ParsedTrade>()
    val transfers = mutableListOf<ParsedExchangeTransfer>()
    val orderMeta = mutableMapOf<String, OrderMeta>()
}

/** Parses one response item into [into] according to its endpoint kind. */
private fun parseExchangeItem(
    obj: JsonObject,
    dataEndpoint: com.moneymanager.domain.model.apistrategy.ApiDataEndpoint,
    requestId: ApiRequestId,
    into: ParsedExchangeData,
) {
    when (dataEndpoint.kind) {
        ApiEndpointKind.TRADES ->
            dataEndpoint.tradeMappings?.let { parseTrade(obj, it, requestId) }?.let(into.trades::add)
        ApiEndpointKind.ORDERS ->
            dataEndpoint.tradeMappings?.let { tm ->
                val orderId = tm.orderIdField?.let { obj.str(it) } ?: obj.str(tm.idField)
                orderId?.let {
                    into.orderMeta[it] =
                        OrderMeta(type = tm.orderTypeField?.let { f -> obj.str(f) }, status = tm.orderStatusField?.let { f -> obj.str(f) })
                }
            }
        ApiEndpointKind.DEPOSITS ->
            dataEndpoint.transactionMappings
                ?.let { parseExchangeTransfer(obj, it, TransferDirection.IN, requestId) }
                ?.let(into.transfers::add)
        ApiEndpointKind.WITHDRAWALS ->
            dataEndpoint.transactionMappings
                ?.let { parseExchangeTransfer(obj, it, TransferDirection.OUT, requestId) }
                ?.let(into.transfers::add)
        ApiEndpointKind.BANK_TRANSACTIONS -> Unit
    }
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
        val dataEndpoint = strategy.dataEndpoints.firstOrNull { request.url.contains(it.endpoint.path) } ?: return@forEach
        val items = responseItemsArray(response.json, dataEndpoint.endpoint.responseArrayKey) ?: return@forEach
        items.forEach { element ->
            (element as? JsonObject)?.let { parseExchangeItem(it, dataEndpoint, response.requestId, parsed) }
        }
    }
    val trades = parsed.trades
    val transfers = parsed.transfers
    val orderMeta = parsed.orderMeta

    // Resolve assets: any code that isn't a known fiat currency is treated as a crypto asset and
    // auto-created (the same rule as the CSV importer), sized to the precision the data actually uses.
    val fiatByCode = currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }
    val cryptoDecimals = mutableMapOf<String, Int>()

    fun noteCrypto(
        code: String?,
        amount: BigDecimal?,
    ) {
        if (code == null || code.uppercase() in fiatByCode) return
        val digits = amount?.let { fractionDigits(it.toString()) } ?: 0
        cryptoDecimals[code.uppercase()] = maxOf(cryptoDecimals[code.uppercase()] ?: 0, digits)
    }
    for (t in trades) {
        noteCrypto(t.baseCode, t.baseQuantity)
        noteCrypto(t.quoteCode, null)
        noteCrypto(t.feeCode, t.feeAmount)
    }
    for (tx in transfers) noteCrypto(tx.currencyCode, tx.amount)

    for ((code, decimals) in cryptoDecimals) {
        val explicit = CryptoRegistry.explicitDecimalsFor(code) ?: 0
        importEngine.createCrypto(
            code,
            name = CryptoRegistry.nameFor(code),
            scaleFactor = scaleFactorForDecimals(maxOf(explicit, decimals)),
            source = source,
        )
    }

    val cryptoByCode = cryptoRepository.getAllCryptoAssets().first().associateBy { it.code.uppercase() }

    fun asset(code: String): Asset? = fiatByCode[code.uppercase()] ?: cryptoByCode[code.uppercase()]

    // Resolve attribute types and the accounts (synthetic + fee + funding) in a first batch so the
    // trades/transfers below can reference real account ids (the engine creates trades before accounts).
    val exchangeAcctAttr = importEngine.getOrCreateAttributeType(EXCHANGE_ACCOUNT_EXTERNAL_ID_ATTR)
    val txnIdAttr = importEngine.getOrCreateAttributeType(EXCHANGE_TXN_ID_ATTR)

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
                        ImportAccountIntent(
                            key = syntheticKey,
                            source = source,
                            match = AccountMatchKey.ByExternalId(exchangeAcctAttr, synthetic.externalId),
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

    trades.forEach forEachTrade@{ t ->
        val baseAsset = asset(t.baseCode)
        val quoteAsset = asset(t.quoteCode)
        if (baseAsset == null || quoteAsset == null) return@forEachTrade
        val baseMoney = Money.fromDisplayValue(t.baseQuantity, baseAsset)
        val quoteMoney = moneyRounded(t.quoteAmount, quoteAsset)
        val orderInfo = t.orderId?.let { orderMeta[it] }
        val description = tradeDescription(t, orderInfo)
        // BUY: quote leaves, base arrives. SELL: base leaves, quote arrives. Both on the one account.
        val fromAmount = if (t.isBuy) quoteMoney else baseMoney
        val toAmount = if (t.isBuy) baseMoney else quoteMoney
        tradeIntents +=
            ImportTradeIntent(
                key = LocalTradeKey("api-$sessionId-trade-${t.id}"),
                source = source,
                timestamp = t.timestamp,
                description = description,
                fromAccountId = syntheticId,
                fromAmount = fromAmount,
                toAccountId = syntheticId,
                toAmount = toAmount,
            )
        // A trade carries no fee slot, so emit any fee as its own movement to the fee account.
        val feeCode = t.feeCode
        val feeAmount = t.feeAmount
        if (feeCode != null && feeAmount != null) {
            val feeAsset = asset(feeCode)
            if (feeAsset != null) {
                transferIntents +=
                    exchangeTransfer(
                        id = "${t.id}-fee",
                        timestamp = t.timestamp,
                        description = "$description fee",
                        fromAccount = syntheticId,
                        toAccount = feeAccountId,
                        money = Money.fromDisplayValue(feeAmount, feeAsset),
                        source = source,
                        requestId = t.requestId,
                        txnIdAttr = txnIdAttr,
                    )
            }
        }
    }

    for (tx in transfers) {
        val txAsset = asset(tx.currencyCode) ?: continue // single jump — allowed
        val money = Money.fromDisplayValue(tx.amount, txAsset)
        val (from, to) =
            if (tx.direction == TransferDirection.IN) fundingId to syntheticId else syntheticId to fundingId
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
                txnIdAttr = txnIdAttr,
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

    val result =
        importEngine.import(
            ImportBatch(
                transfers = transferIntents,
                trades = tradeIntents,
                dedupePolicy =
                    DedupePolicy.ApiMultiKey(
                        reconciledExclusionAttributeTypeId =
                            if (bridges.isEmpty()) null else AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID),
                        reconciledRelationshipTypeId =
                            if (bridges.isEmpty()) null else RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID),
                        internalTransferBridges = bridges,
                        internalTransferWindow = reconcile?.windowSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
                        internalTransferAmountTolerancePct = reconcile?.amountTolerancePct ?: 0.0,
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
    )
}

private fun exchangeTransfer(
    id: String,
    timestamp: Instant,
    description: String,
    fromAccount: AccountId,
    toAccount: AccountId,
    money: Money,
    source: Source,
    requestId: ApiRequestId,
    txnIdAttr: AttributeTypeId,
): ImportTransfer =
    ImportTransfer(
        source = source,
        rowKey = ImportRowKey.ApiJsonPath(requestId, "exchange:$id"),
        fromAccount = AccountRef.Existing(fromAccount),
        toAccount = AccountRef.Existing(toAccount),
        timestamp = timestamp,
        description = description,
        amount = money,
        apiId = id,
        attributes = listOf(NewAttribute(txnIdAttr, id)),
    )

private fun tradeDescription(
    trade: ParsedTrade,
    order: OrderMeta?,
): String {
    val verb = if (trade.isBuy) "Buy" else "Sell"
    val base = "$verb ${trade.baseCode}/${trade.quoteCode}"
    val suffix = order?.type?.let { " ($it)" } ?: ""
    return base + suffix
}

private fun parseTrade(
    obj: JsonObject,
    tm: ApiTradeMappings,
    requestId: ApiRequestId,
): ParsedTrade? {
    val instrument = obj.str(tm.instrumentField) ?: return null
    val (baseCode, quoteCode) = splitInstrument(instrument, tm) ?: return null
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
        feeCode = tm.feeCurrencyField?.let { obj.str(it) },
        orderId = tm.orderIdField?.let { obj.str(it) },
        requestId = requestId,
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
        InstrumentSplitMode.EXPLICIT_FIELDS -> null // handled by caller when base/quote fields set; unused for now
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
): ParsedExchangeTransfer? {
    val amount = obj.str(tm.amountField)?.let { runCatching { BigDecimal(it).abs() }.getOrNull() } ?: return null
    val currency = obj.str(tm.currencyField) ?: return null
    val timestamp = obj.str(tm.timestampField)?.let { parseApiTimestamp(it, tm.timestampFormat) } ?: return null
    val id = obj.str(tm.idField) ?: return null
    val description =
        obj.str(tm.descriptionField)?.takeIf { it.isNotBlank() }
            ?: if (direction == TransferDirection.IN) "Deposit $currency" else "Withdraw $currency"
    return ParsedExchangeTransfer(id, timestamp, currency, amount, direction, description, requestId)
}

/** Rounds a positive display value to its asset's minor units (half-up), avoiding fromDisplayValue's
 * exact-precision requirement for a computed leg (quote = quantity × price). */
private fun moneyRounded(
    displayValue: BigDecimal,
    asset: Asset,
): Money {
    val scaled = displayValue.abs() * BigDecimal(asset.scaleFactor)
    val minor = (scaled + HALF).toLong()
    return Money(BigInteger(minor), asset)
}

private val HALF = BigDecimal("0.5")

/** Significant fractional digits of a decimal string (trailing zeros ignored). */
private fun fractionDigits(value: String): Int = value.substringAfter('.', "").trimEnd('0').length

private fun scaleFactorForDecimals(decimals: Int): Long {
    var factor = 1L
    repeat(decimals.coerceIn(0, 18)) { factor *= 10 }
    return factor
}

private fun JsonObject.str(path: String): String? =
    (resolveJsonPathElement(path) as? kotlinx.serialization.json.JsonPrimitive)?.let { it.contentOrNullCompat() }

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
