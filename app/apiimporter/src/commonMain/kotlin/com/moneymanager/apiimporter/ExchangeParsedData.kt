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

internal data class ParsedTrade(
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
    /** The value of the `excluded` attribute when the rules exclude this trade from balances. */
    val excludedValue: String? = null,
    /** The attributes the rules tag this trade with. */
    val ruleAttributes: List<RuleAttribute> = emptyList(),
)

internal data class ParsedExchangeTransfer(
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
    /**
     * The counterparty account the rules name (e.g. "Binance Earn" for a Simple Earn subscription, "Crypto.com"
     * for an internal transfer) — used when no wallet address names one, in place of the strategy-wide
     * funding account.
     */
    val counterpartyAccountName: String?,
    /** Value looked up against an [ApiDataEndpoint.enrichesTransfers] index (e.g. Kraken Ledgers `refid`). */
    val joinKey: String?,
    /**
     * When true, this movement is booked straight to the fee account (exchange -> Fees), never to
     * the counterparty/funding side. Produced by a fee rule on a ledger row, even one whose main
     * [ApiTransactionMappings.amountField] became a ledger trade leg instead (Kraken's
     * `type=trade` ledger rows carry a same-asset settlement fee TradesHistory never reports, alongside
     * an `amount` that duplicates the trade TradesHistory already supplied).
     */
    val isFeeOnly: Boolean = false,
    /** See [ImportTransfer.approximateUntil]: set when [timestamp] is only the fetch window's start. */
    val approximateUntil: Instant? = null,
    /** The value of the `excluded` attribute when the rules exclude this movement from balances. */
    val excludedValue: String? = null,
    /** The attributes the rules tag this movement with. */
    val ruleAttributes: List<RuleAttribute> = emptyList(),
    /** The observation slot of its JSON item it fills: the item's own movement, or the fee it reports beside it. */
    val observationSlot: String = ObservationSlot.MAIN,
)

internal data class ParsedOrder(
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
internal data class ParsedEnrichment(
    val id: String,
    val address: String?,
    val network: String?,
    val txid: String?,
)

/**
 * One leg of a trade-type ledger row kept out of [ParsedExchangeData.transfers] as a duplicate of a
 * `TradesHistory` trade (see [ApiTransactionMappings.ledgerTrades]) — kept so the trade's
 * booked amount can be reconciled against what the ledger actually settled.
 */
internal data class ParsedLedgerTradeLeg(
    val refid: String,
    val assetCode: String,
    val signedAmount: BigDecimal,
    val timestamp: Instant,
    val requestId: ApiRequestId,
    val jsonPath: String,
    /**
     * The other side of the trade as this row itself reports it (see
     * [ApiLedgerTrades.unpairedCounterAmountPath]) — used only when no other ledger row
     * records it, i.e. the trade was paid for from outside the exchange.
     */
    val counterAssetCode: String? = null,
    val counterAmount: BigDecimal? = null,
    val fundingAccountName: String? = null,
)

/** [reconcileTradesAgainstLedger]'s trades, plus the funding movements its single-leg trades need. */
internal data class LedgerReconciledTrades(
    val trades: List<ParsedTrade>,
    val fundingTransfers: List<ParsedExchangeTransfer>,
)

/**
 * The balance an item reports the exchange account holding in [currencyCode] once it has happened (the `balance` rule
 * action: Kraken's Ledgers `balance`), whatever the item itself books: a transfer, a fee or a ledger trade leg.
 */
internal data class ParsedReportedBalance(
    val timestamp: Instant,
    val currencyCode: String,
    val amount: BigDecimal,
    val requestId: ApiRequestId,
    val jsonPath: String,
)

/** Accumulator for parsed items across all of a session's responses. */
internal class ParsedExchangeData {
    val trades = mutableListOf<ParsedTrade>()
    val transfers = mutableListOf<ParsedExchangeTransfer>()
    val orders = mutableListOf<ParsedOrder>()
    val enrichments = mutableListOf<ParsedEnrichment>()
    val ledgerTradeLegs = mutableListOf<ParsedLedgerTradeLeg>()
    val balances = mutableListOf<ParsedReportedBalance>()
}
