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

/**
 * Reconciles parsed trades against their authoritative ledger legs (see
 * [ApiTransactionMappings.ledgerTrades]): a trade's leg amounts are overridden by the
 * matching ledger group's signed amounts where present, and any ledger group matching no trade at all
 * is booked as its own trade — Kraken's Ledgers `balance` is ground truth, so no movement it reports
 * may be silently dropped or left at a TradesHistory amount it disagrees with. A group of one leg that
 * names its own counter amount (see [ApiLedgerTrades.unpairedCounterAmountPath]) is
 * booked as a trade against that amount, funded by a transfer so the counter asset nets to zero.
 */
internal fun reconcileTradesAgainstLedger(
    rawTrades: List<ParsedTrade>,
    ledgerLegs: List<ParsedLedgerTradeLeg>,
    canonicalAsset: (String) -> String,
): LedgerReconciledTrades {
    val trades =
        rawTrades.map {
            it.copy(
                baseCode = canonicalAsset(it.baseCode),
                quoteCode = canonicalAsset(it.quoteCode),
                feeCode = it.feeCode?.let(canonicalAsset),
            )
        }
    val legsByRefid =
        ledgerLegs
            .map { it.copy(assetCode = canonicalAsset(it.assetCode), counterAssetCode = it.counterAssetCode?.let(canonicalAsset)) }
            .groupBy { it.refid }
    if (legsByRefid.isEmpty()) return LedgerReconciledTrades(trades, emptyList())

    val consumedRefids = mutableSetOf<String>()

    // A trade's own id equals its ledger refid for an ordinary fill; a Kraken "synthetic_pair"
    // crypto/crypto trade's id never appears as a ledger refid at all, so fall back to the only other
    // thing that reliably ties them together: the same second and the same {base, quote} asset pair.
    fun legsFor(trade: ParsedTrade): List<ParsedLedgerTradeLeg>? {
        legsByRefid[trade.id]?.let {
            consumedRefids += trade.id
            return it
        }
        val tradeSecond = trade.timestamp.epochSeconds
        val assets = setOf(trade.baseCode, trade.quoteCode)
        val match =
            legsByRefid.entries.firstOrNull { (refid, legs) ->
                refid !in consumedRefids &&
                    legs.any { it.timestamp.epochSeconds == tradeSecond } &&
                    legs.map { it.assetCode }.toSet() == assets
            }
        match?.let { consumedRefids += it.key }
        return match?.value
    }

    val reconciled =
        trades.map { trade ->
            val legs = legsFor(trade) ?: return@map trade
            val baseLeg = legs.firstOrNull { it.assetCode == trade.baseCode }
            val quoteLeg = legs.firstOrNull { it.assetCode == trade.quoteCode }
            trade.copy(
                baseQuantity = baseLeg?.signedAmount?.abs() ?: trade.baseQuantity,
                quoteAmount = quoteLeg?.signedAmount?.abs() ?: trade.quoteAmount,
            )
        }

    val unmatchedGroups = legsByRefid.entries.filterNot { it.key in consumedRefids }
    val singleLegGroups =
        unmatchedGroups.mapNotNull { (refid, legs) ->
            legs.singleOrNull()?.takeIf { it.counterAmount != null }?.let {
                refid to
                    it
            }
        }
    val fundingTransfers = mutableListOf<ParsedExchangeTransfer>()
    val singleLegTrades =
        singleLegGroups.mapNotNull { (refid, leg) ->
            val counterCode = leg.counterAssetCode ?: return@mapNotNull null
            val counterAmount = leg.counterAmount?.abs() ?: return@mapNotNull null
            if (leg.signedAmount == BigDecimal.ZERO || counterAmount == BigDecimal.ZERO) return@mapNotNull null
            // An incoming leg was bought with money that came from outside (a card), an outgoing one
            // sold for money that left the exchange (a bank payout).
            val bought = leg.signedAmount > BigDecimal.ZERO
            fundingTransfers +=
                ParsedExchangeTransfer(
                    id = "$refid-funding",
                    timestamp = leg.timestamp,
                    currencyCode = counterCode,
                    amount = counterAmount,
                    direction = if (bought) TransferDirection.IN else TransferDirection.OUT,
                    description = if (bought) "Deposit $counterCode" else "Withdraw $counterCode",
                    requestId = leg.requestId,
                    jsonPath = leg.jsonPath,
                    counterpartyAddress = null,
                    network = null,
                    txid = null,
                    counterpartyAccountName = leg.fundingAccountName,
                    joinKey = null,
                )
            ParsedTrade(
                id = refid,
                timestamp = leg.timestamp,
                isBuy = bought,
                baseCode = leg.assetCode,
                quoteCode = counterCode,
                baseQuantity = leg.signedAmount.abs(),
                quoteAmount = counterAmount,
                feeAmount = null,
                feeCode = null,
                orderId = null,
                requestId = leg.requestId,
                jsonPath = leg.jsonPath,
            )
        }

    val orphanTrades =
        unmatchedGroups
            .mapNotNull { (refid, legs) ->
                if (legs.size > 2) return@mapNotNull aggregatedLegsTrade(refid, legs)
                if (legs.size != 2) return@mapNotNull null
                val outLeg = legs.firstOrNull { it.signedAmount < BigDecimal.ZERO } ?: return@mapNotNull null
                val inLeg = legs.firstOrNull { it.signedAmount > BigDecimal.ZERO } ?: return@mapNotNull null
                ParsedTrade(
                    id = refid,
                    timestamp = outLeg.timestamp,
                    isBuy = false,
                    baseCode = outLeg.assetCode,
                    quoteCode = inLeg.assetCode,
                    baseQuantity = outLeg.signedAmount.abs(),
                    quoteAmount = inLeg.signedAmount.abs(),
                    feeAmount = null,
                    feeCode = null,
                    orderId = null,
                    requestId = outLeg.requestId,
                    jsonPath = outLeg.jsonPath,
                )
            }
    return LedgerReconciledTrades(reconciled + orphanTrades + singleLegTrades, fundingTransfers)
}

/**
 * One trade from a ledger group of several rows — an order filled in parts, each fill posting a row per
 * asset — when the rows net to exactly one asset leaving and one arriving; null otherwise.
 */
private fun aggregatedLegsTrade(
    refid: String,
    legs: List<ParsedLedgerTradeLeg>,
): ParsedTrade? {
    val netByAsset =
        legs
            .groupBy { it.assetCode }
            .mapValues { (_, assetLegs) -> assetLegs.fold(BigDecimal.ZERO) { total, leg -> total + leg.signedAmount } }
    if (netByAsset.size != 2) return null
    val (outCode, outNet) = netByAsset.entries.firstOrNull { it.value < BigDecimal.ZERO } ?: return null
    val (inCode, inNet) = netByAsset.entries.firstOrNull { it.value > BigDecimal.ZERO } ?: return null
    val first = legs.minBy { it.timestamp }
    return ParsedTrade(
        id = refid,
        timestamp = first.timestamp,
        isBuy = false,
        baseCode = outCode,
        quoteCode = inCode,
        baseQuantity = outNet.abs(),
        quoteAmount = inNet,
        feeAmount = null,
        feeCode = null,
        orderId = null,
        requestId = first.requestId,
        jsonPath = first.jsonPath,
    )
}
