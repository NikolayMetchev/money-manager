package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.excludedAttributeValue
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.tasks.PhasedProgress
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Parses one response item into [into] according to its endpoint kind. */
internal fun parseExchangeItem(
    obj: JsonObject,
    rules: RuleEngine,
    dataEndpoint: ApiDataEndpoint,
    requestId: ApiRequestId,
    jsonPath: String,
    into: ParsedExchangeData,
    windowStart: Instant?,
    windowEnd: Instant?,
) {
    if (dataEndpoint.enrichesTransfers) {
        dataEndpoint.transactionMappings?.let { parseEnrichment(obj, it) }?.let(into.enrichments::add)
        return
    }
    when (dataEndpoint.kind) {
        ApiEndpointKind.TRADES -> {
            dataEndpoint.tradeMappings?.let { parseTrade(obj, rules, it, requestId, jsonPath, windowStart) }?.let(into.trades::add)
        }

        ApiEndpointKind.ORDERS -> {
            dataEndpoint.tradeMappings?.let { parseOrder(obj, rules, it, requestId, jsonPath) }?.let(into.orders::add)
        }

        ApiEndpointKind.DEPOSITS -> {
            dataEndpoint.transactionMappings
                ?.let {
                    parseExchangeTransfer(
                        obj,
                        rules,
                        it,
                        TransferDirection.IN,
                        requestId,
                        jsonPath,
                        into,
                        windowStart,
                        windowEnd,
                    )
                }?.let(into.transfers::addAll)
        }

        ApiEndpointKind.WITHDRAWALS -> {
            dataEndpoint.transactionMappings
                ?.let {
                    parseExchangeTransfer(
                        obj,
                        rules,
                        it,
                        TransferDirection.OUT,
                        requestId,
                        jsonPath,
                        into,
                        windowStart,
                        windowEnd,
                    )
                }?.let(into.transfers::addAll)
        }

        ApiEndpointKind.BANK_TRANSACTIONS -> {}
    }
}

internal fun parseEnrichment(
    obj: JsonObject,
    tm: ApiTransactionMappings,
): ParsedEnrichment? {
    val id = obj.resolveJsonPath(tm.idField) ?: return null
    return ParsedEnrichment(
        id = id,
        address = tm.counterpartyAddressField?.let { obj.resolveJsonPath(it) }?.takeIf { it.isNotBlank() },
        network = tm.counterpartyNetworkField?.let { obj.resolveJsonPath(it) }?.takeIf { it.isNotBlank() },
        txid = tm.txidField?.let { obj.resolveJsonPath(it) }?.takeIf { it.isNotBlank() },
    )
}

/** The `excluded` attribute value these rules give the record, or null when they leave it counted. */
internal fun RuleOutcome.excludedValue(): String? = if (excluded) excludedAttributeValue(excludeReason) else null

internal fun parseOrder(
    obj: JsonObject,
    rules: RuleEngine,
    tm: ApiTradeMappings,
    requestId: ApiRequestId,
    jsonPath: String,
): ParsedOrder? {
    val outcome = rules.evaluate(tm.rules, JsonRecord(obj))
    if (outcome.skipped) return null
    val item = outcome.record
    val orderRef = (tm.orderIdField?.let { item.text(it) } ?: item.text(tm.idField)) ?: return null
    val side = (outcome.tradeBuy ?: return null).let { if (it) "BUY" else "SELL" }
    val createdAt =
        item.text(tm.timestampField)?.let { parseApiTimestamp(it, tm.timestampFormat, tm.timestampPattern) } ?: return null
    return ParsedOrder(
        orderRef = orderRef,
        clientOid = tm.clientOidField?.let { item.text(it) },
        side = side,
        orderType = tm.orderTypeField?.let { item.text(it) },
        timeInForce = tm.timeInForceField?.let { item.text(it) },
        status = tm.orderStatusField?.let { item.text(it) },
        limitPrice = tm.limitPriceField?.let { item.text(it) },
        quantity = item.text(tm.baseQuantityField),
        avgPrice = tm.avgPriceField?.let { item.text(it) },
        createdAt = createdAt,
        updatedAt = tm.updateTimestampField?.let { item.text(it) }?.let { parseApiTimestamp(it, tm.timestampFormat, tm.timestampPattern) },
        requestId = requestId,
        jsonPath = jsonPath,
    )
}

/**
 * An item's timestamp, or [windowStart] when the provider reports the epoch itself (`0`) — a placeholder
 * for "unknown" (Bybit's oldest deposit records carry `successAt = "0"` and no other date). The start of the
 * date window the row was fetched in is the earliest moment it can have happened, so booking it there is
 * at most one window off and never later than movements that depended on it.
 * A row with no timestamp field at all is still skipped, as before.
 */
internal fun itemTimestamp(
    raw: String?,
    format: TimestampFormat,
    pattern: String?,
    windowStart: Instant?,
): Instant? {
    val parsed = raw?.let { parseApiTimestamp(it, format, pattern) } ?: return null
    return if (parsed.toEpochMilliseconds() <= 0L && windowStart != null) windowStart else parsed
}

/**
 * The latest moment an item booked at its fetch window's start (see [itemTimestamp]) can have happened —
 * the window's end — or null when the item carried a real timestamp.
 */
internal fun approximateUntil(
    raw: String?,
    format: TimestampFormat,
    pattern: String?,
    windowStart: Instant?,
    windowEnd: Instant?,
): Instant? {
    if (windowStart == null || windowEnd == null) return null
    val parsed = raw?.let { parseApiTimestamp(it, format, pattern) } ?: return null
    return windowEnd.takeIf { parsed.toEpochMilliseconds() <= 0L }
}

internal fun parseTrade(
    obj: JsonObject,
    rules: RuleEngine,
    tm: ApiTradeMappings,
    requestId: ApiRequestId,
    jsonPath: String,
    windowStart: Instant?,
): ParsedTrade? {
    val outcome = rules.evaluate(tm.rules, JsonRecord(obj))
    if (outcome.skipped) return null
    val item = outcome.record
    val (baseCode, quoteCode) =
        if (tm.splitMode == InstrumentSplitMode.EXPLICIT_FIELDS) {
            val base = tm.baseAssetField?.let { item.text(it) }
            val quote = tm.quoteAssetField?.let { item.text(it) }
            if (base != null && quote != null) base to quote else return null
        } else {
            val instrument = tm.instrumentField?.let { item.text(it) } ?: return null
            splitInstrument(instrument, tm) ?: return null
        }
    val isBuy = outcome.tradeBuy ?: return null
    val baseQty = item.text(tm.baseQuantityField)?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: return null
    val quoteAmount =
        tm.quoteQuantityField?.let { item.text(it)?.let { v -> runCatching { BigDecimal(v) }.getOrNull() } }
            ?: tm.priceField?.let { item.text(it)?.let { p -> runCatching { baseQty * BigDecimal(p) }.getOrNull() } }
            ?: return null
    val timestamp =
        itemTimestamp(item.text(tm.timestampField), tm.timestampFormat, tm.timestampPattern, windowStart) ?: return null
    val id = outcome.externalId ?: item.text(tm.idField) ?: return null
    val fee = outcome.fees.firstOrNull { rules.evaluator.all(it.conditions, item) }
    return ParsedTrade(
        id = id,
        timestamp = timestamp,
        isBuy = isBuy,
        baseCode = baseCode,
        quoteCode = quoteCode,
        baseQuantity = baseQty.abs(),
        quoteAmount = quoteAmount.abs(),
        feeAmount =
            fee
                ?.let { rules.resolve(it.amount, item) }
                ?.takeIf { it.isNotBlank() }
                ?.let { v -> runCatching { BigDecimal(v).abs() }.getOrNull() },
        // Most exchanges charge the trade fee in the quote asset without a separate field for it
        // (Kraken); an explicit fee currency (Crypto.com) overrides this default.
        feeCode = fee?.currency?.let { rules.resolve(it, item) }?.takeIf { it.isNotBlank() } ?: quoteCode,
        orderId = tm.orderIdField?.let { item.text(it) },
        requestId = requestId,
        jsonPath = jsonPath,
        excludedValue = outcome.excludedValue(),
        ruleAttributes = outcome.attributes,
    )
}

internal fun splitInstrument(
    instrument: String,
    tm: ApiTradeMappings,
): Pair<String, String>? =
    when (tm.splitMode) {
        InstrumentSplitMode.SEPARATOR -> {
            val parts = instrument.split(tm.instrumentSeparator)
            if (parts.size >= 2) parts[0] to parts[1] else null
        }

        // EXPLICIT_FIELDS is resolved directly in parseTrade from the base/quote asset fields.
        InstrumentSplitMode.EXPLICIT_FIELDS -> {
            null
        }

        InstrumentSplitMode.QUOTE_SUFFIX -> {
            // Kraken marks some crypto/crypto pairs as "synthetic_pair" and reports them
            // slash-separated (e.g. "SUI/BTC") instead of concatenated like its usual pairs
            // ("XXBTZGBP") — an explicit separator always wins over suffix matching.
            val slashIndex = instrument.indexOf('/')
            if (slashIndex >= 0) {
                val base = instrument.substring(0, slashIndex)
                val quote = instrument.substring(slashIndex + 1)
                if (base.isNotEmpty() && quote.isNotEmpty()) base to quote else null
            } else {
                val quote = tm.quoteAssets.filter { instrument.endsWith(it) }.maxByOrNull { it.length }
                quote?.let { instrument.removeSuffix(it) to it }
            }
        }
    }

internal fun parseExchangeTransfer(
    obj: JsonObject,
    rules: RuleEngine,
    tm: ApiTransactionMappings,
    direction: TransferDirection,
    requestId: ApiRequestId,
    jsonPath: String,
    into: ParsedExchangeData,
    windowStart: Instant? = null,
    windowEnd: Instant? = null,
): List<ParsedExchangeTransfer> {
    val outcome = rules.evaluate(tm.rules, JsonRecord(obj))
    if (outcome.skipped) return emptyList()
    val item = outcome.record
    val currency = item.text(tm.currencyField) ?: return emptyList()
    val rawTimestamp = item.text(tm.timestampField)
    val timestamp =
        itemTimestamp(rawTimestamp, tm.timestampFormat, tm.timestampPattern, windowStart) ?: return emptyList()
    val approximateUntil = approximateUntil(rawTimestamp, tm.timestampFormat, tm.timestampPattern, windowStart, windowEnd)
    val id = outcome.externalId ?: item.text(tm.idField) ?: return emptyList()
    val rawAmount = item.text(tm.amountField)?.let { runCatching { BigDecimal(it) }.getOrNull() }
    val fee = outcome.fees.firstOrNull { rules.evaluator.carriesFee(it, item, currency) }
    val rawFeeAmount =
        fee
            ?.let {
                rules.resolve(it.amount, item)
            }?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
    val feeCurrency = fee?.currency?.let { rules.resolve(it, item) }?.takeIf { it.isNotBlank() } ?: currency
    // A fee included in the amount that is all of it: the item is itself a fee, booked to the own account's fee account.
    val wholeFee =
        fee?.includedInAmount == true &&
            rawAmount != null &&
            rawFeeAmount != null &&
            rawAmount.compareTo(BigDecimal.ZERO) != 0 &&
            feeCurrency.equals(currency, ignoreCase = true) &&
            rawFeeAmount.abs().compareTo(rawAmount.abs()) == 0

    outcome.balance?.let { balance ->
        val amount =
            rules
                .resolve(balance.amount, item)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { BigDecimal(it) }.getOrNull() }
        if (amount != null) {
            // When the feed's running balance counts the item, in the item's own timestamp format; else its own time.
            val countedAt =
                balance.at?.let { rules.resolve(it, item).trim() }?.takeIf { it.isNotEmpty() }?.let { raw ->
                    itemTimestamp(raw, tm.timestampFormat, tm.timestampPattern, windowStart)
                        ?: error("Failed to parse balance time: $raw")
                }
            into.balances +=
                ParsedReportedBalance(
                    timestamp = countedAt ?: timestamp,
                    currencyCode = balance.currency?.let { rules.resolve(it, item).trim() }?.takeIf { it.isNotEmpty() } ?: currency,
                    amount = amount,
                    requestId = requestId,
                    jsonPath = jsonPath,
                )
        }
    }

    val result = mutableListOf<ParsedExchangeTransfer>()
    if (outcome.ledgerTradeLeg) {
        val ledgerTrades = tm.ledgerTrades
        val refid = ledgerTrades?.let { rules.resolve(it.key, item) }?.takeIf { it.isNotBlank() }
        if (ledgerTrades != null && refid != null && rawAmount != null) {
            val counterField = ledgerTrades.unpairedCounterAmountPath
            into.ledgerTradeLegs +=
                ParsedLedgerTradeLeg(
                    refid = refid,
                    assetCode = currency,
                    signedAmount = rawAmount,
                    timestamp = timestamp,
                    requestId = requestId,
                    jsonPath = jsonPath,
                    counterAssetCode = counterField?.let { item.text("$it.currency") },
                    counterAmount = counterField?.let { item.text("$it.amount") }?.let { runCatching { BigDecimal(it) }.getOrNull() },
                    fundingAccountName = ledgerTrades.unpairedFundingAccountName,
                )
        }
    } else if (rawAmount != null) {
        // Unclaimed, the endpoint's own direction. Some exchanges (Kraken) book a failed/cancelled deposit
        // or withdrawal as a second ledger entry sharing the same id with the opposite sign of the original,
        // netting to zero, so their rules take the direction from the amount's sign instead; an endpoint
        // listing both directions with unsigned amounts (Bybit Earn's Stake/Redeem orders) names it in a field.
        val resolvedDirection = outcome.incoming?.let { if (it) TransferDirection.IN else TransferDirection.OUT } ?: direction
        // A gross amount already includes its fee: carve a same-asset fee out so the two sum back to it.
        val amount =
            if (!wholeFee && fee?.includedInAmount == true && rawFeeAmount != null && feeCurrency.equals(currency, ignoreCase = true)) {
                (rawAmount.abs() - rawFeeAmount.abs()).let { if (it < BigDecimal.ZERO) BigDecimal.ZERO else it }
            } else {
                rawAmount.abs()
            }
        val description =
            item.text(tm.descriptionField)?.takeIf { it.isNotBlank() }
                ?: if (resolvedDirection == TransferDirection.IN) "Deposit $currency" else "Withdraw $currency"
        val counterparty = rules.counterparty(outcome.accountsFor(AccountSide.COUNTERPARTY), item)
        result +=
            ParsedExchangeTransfer(
                id = id,
                timestamp = timestamp,
                currencyCode = currency,
                amount = amount,
                direction = resolvedDirection,
                description = description,
                requestId = requestId,
                jsonPath = jsonPath,
                counterpartyAddress = tm.counterpartyAddressField?.let { item.text(it) }?.takeIf { it.isNotBlank() },
                network = tm.counterpartyNetworkField?.let { item.text(it) }?.takeIf { it.isNotBlank() },
                txid = tm.txidField?.let { item.text(it) }?.takeIf { it.isNotBlank() },
                counterpartyAccountName = counterparty?.first?.takeUnless { wholeFee },
                joinKey = tm.joinKeyField?.let { item.text(it) },
                isFeeOnly = wholeFee,
                approximateUntil = approximateUntil,
                excludedValue = outcome.excludedValue(),
                ruleAttributes = outcome.attributes,
            )
    }

    // Kraken's ledger charges a same-asset settlement fee on some rows (visible only here, never in
    // TradesHistory's own quote-currency fee) — extracted unconditionally, even when the row's main
    // amount became a ledger trade leg as a trade-type duplicate, since the fee itself is not a duplicate of anything.
    // A failed/reversed deposit or withdrawal's reversal row carries the *negated* fee (a refund of the
    // original charge) — abs()-ing it would book the refund as a second charge instead of crediting it
    // back, so the sign decides direction: negative -> refund (Fees -> exchange), positive -> charge
    // (exchange -> Fees).
    // A fee every leg of a fill repeats is charged once, on the leg in the pair's quote asset.
    if (!wholeFee && rawFeeAmount != null && rawFeeAmount != BigDecimal.ZERO) {
        val isRefund = rawFeeAmount < BigDecimal.ZERO
        result +=
            ParsedExchangeTransfer(
                id = "$id-fee",
                timestamp = timestamp,
                currencyCode = feeCurrency,
                amount = rawFeeAmount.abs(),
                direction = if (isRefund) TransferDirection.IN else TransferDirection.OUT,
                description =
                    fee.description?.let { rules.resolve(it, item) }?.takeIf { it.isNotBlank() }
                        ?: if (isRefund) "$feeCurrency fee refund" else "$feeCurrency fee",
                requestId = requestId,
                jsonPath = jsonPath,
                counterpartyAddress = null,
                network = null,
                txid = null,
                counterpartyAccountName = null,
                joinKey = null,
                isFeeOnly = true,
                approximateUntil = approximateUntil,
                // A fee charged for an excluded movement is excluded with it.
                excludedValue = outcome.excludedValue(),
                observationSlot = ObservationSlot.FEE,
            )
    }
    return result
}

internal fun RuleEngine.resolve(
    expr: ValueExpr,
    item: Record,
): String = evaluator.resolve(expr) { item.text(it) }

/** The name the first of [namings] that names an account for [item] gives, with that naming, or null. */
internal fun RuleEngine.counterparty(
    namings: List<AccountNaming>,
    item: Record,
): Pair<String, AccountNaming>? =
    namings.firstNotNullOfOrNull { naming ->
        evaluator
            .name(naming) { item.text(it) }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { it to naming }
    }

/** Parses every stored response of [sessionId] into [ParsedExchangeData], reporting progress on [bar]. */
internal suspend fun parseSessionResponses(
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    bar: PhasedProgress,
): ParsedExchangeData {
    bar.emit(base = 0f, detail = "Reading downloaded responses")
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val responses = apiSessionRepository.getResponsesBySession(sessionId)

    val parsed = ParsedExchangeData()
    val rules = RuleEngine()
    responses.forEachIndexed forEachResponse@{ responseIndex, response ->
        if (responseIndex % PARSE_PROGRESS_EVERY_RESPONSES == 0) {
            bar.emit(
                base = PARSE_BASE,
                span = PARSE_SPAN,
                detail = "Parsing responses",
                innerFraction = responseIndex.toFloat() / responses.size,
                processed = responseIndex,
                total = responses.size,
            )
        }
        val request = requestsById[response.requestId] ?: return@forEachResponse
        val windowStart = windowStartFromMarker(request.url)
        val windowEnd = windowEndFromMarker(request.url)
        // Prefer the precise "ep=<key>" marker (disambiguates endpoints sharing a path, e.g. Kraken's
        // Ledgers deposit/withdrawal split); a QUERY_ONLY signed URL (Binance) carries no marker, so fall
        // back to a plain path match, which is unambiguous there since query params live in the URL.
        val dataEndpoint =
            strategy.config.dataEndpoints.firstOrNull { request.url.contains("ep=${endpointDedupeKey(it.endpoint)}") }
                ?: strategy.config.dataEndpoints.firstOrNull { request.url.contains(it.endpoint.path) }
                ?: return@forEachResponse
        val items =
            responseItemsWithKeys(
                response.json,
                dataEndpoint.endpoint.responseArrayKey,
                dataEndpoint.endpoint.responseObjectValues,
                dataEndpoint.endpoint.itemKeyField,
                dataEndpoint.endpoint.nestedItemsKey,
            ) ?: return@forEachResponse
        items.forEachIndexed { index, (key, element) ->
            (element as? JsonObject)?.let {
                // The real JSON path of this item so the audit view can expand to the exact node —
                // the object's own key for a keyed-object response (Kraken), else an array index.
                val jsonPath =
                    if (key != null) {
                        keyedItemJsonPath(dataEndpoint.endpoint.responseArrayKey, key).value
                    } else {
                        arrayItemJsonPath(dataEndpoint.endpoint.responseArrayKey, index).value
                    }
                parseExchangeItem(it, rules, dataEndpoint, response.requestId, jsonPath, parsed, windowStart, windowEnd)
            }
        }
    }
    return parsed
}
