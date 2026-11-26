package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * How a timestamp value is encoded in an API response.
 *
 * [ISO_8601] — an ISO-8601 string parsed via `Instant.parse` (bank APIs: Monzo/Wise/Starling).
 * [EPOCH_MS]/[EPOCH_S] — an integer count of milliseconds/seconds since the epoch (most exchanges).
 * [EPOCH_S_FLOAT] — a fractional count of seconds since the epoch (e.g. Kraken `1499.234`).
 */
@Serializable
enum class TimestampFormat {
    ISO_8601,
    EPOCH_MS,
    EPOCH_S,
    EPOCH_S_FLOAT,

    /**
     * A custom date-time pattern (e.g. Binance withdrawal history's `"yyyy-MM-dd HH:mm:ss"`, always
     * UTC), given per-mapping in `timestampPattern`. Not a Java/ICU pattern — see
     * `parseApiTimestamp` (`app:apiimporter`) for the small set of tokens supported.
     */
    PATTERN,
}

/** How a transaction amount value is encoded in the API response. */
@Serializable
enum class ApiAmountFormat {
    /** Integer amount already expressed in minor currency units (e.g. Monzo: 1234 == 12.34). */
    MINOR_UNITS_INTEGER,

    /** Decimal amount in major currency units (e.g. Wise: "12.34"); converted via Money. */
    DECIMAL_MAJOR_UNITS,
}

/**
 * How a ledger's trade-type rows — the rows a [RuleAction.LedgerTradeLeg] rule takes out of the transfers —
 * group back into trades: rows whose [key] agrees are one trade's legs.
 *
 * Each group's signed amounts are authoritative: a trade another endpoint reports with the same id (or,
 * failing that, the same second and asset pair) takes its leg amounts from the group — Kraken's
 * TradesHistory `cost` is a display-rounded price*volume for synthetic crypto/crypto pairs and can
 * disagree with what the ledger actually settled — and a group no trade claims is booked as its own
 * trade when its legs net to one asset out and one asset in, so no movement the ledger reports is ever
 * silently dropped.
 *
 * @property key The trade a row belongs to — Kraken Ledgers `refid`; Coinbase `buy.id`, `sell.id` or
 *   `trade.id` (the first non-blank of its paths).
 * @property unpairedCounterAmountPath Dot-path to an amount object (`{amount, currency}`) giving the other
 *   side of a group of only one leg — a purchase paid for straight from a card or bank, so no ledger row
 *   records the money leaving (Coinbase `native_amount`). Such a group is booked as a trade against that
 *   amount, plus a transfer of it between [unpairedFundingAccountName] and the exchange account so the
 *   exchange's balance of that asset still nets to zero. Null drops single-leg groups.
 * @property unpairedFundingAccountName The account such a trade is funded from (a purchase) or paid out
 *   to (a sale); null uses the strategy-wide "<account> Funding".
 */
@Serializable
data class ApiLedgerTrades(
    val key: ValueExpr,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unpairedCounterAmountPath: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unpairedFundingAccountName: String? = null,
)

/**
 * JSON field names used to extract transaction data from an API response item. Defaults match the
 * Monzo response shape so existing strategies behave identically.
 *
 * @property amountField Dot-path to the amount value (e.g. "amount" or "amount.value")
 * @property timestampField Dot-path to the ISO-8601 timestamp (e.g. "created", "date")
 * @property currencyField Dot-path to the ISO-4217 currency code (e.g. "currency", "amount.currency")
 * @property descriptionField Dot-path to the transaction description
 * @property amountFormat How [amountField] is encoded; see [ApiAmountFormat]
 * @property idField Dot-path to the transaction's stable id, used for de-duplication
 * @property ledgerTrades How a ledger's trade-type rows group into trades (see [ApiLedgerTrades]).
 * @property rules What an item decides, run over its raw JSON in order (first claim wins).
 *   - A bank feed: the direction ([RuleAction.SetDirection]; unclaimed, the amount's sign), a decline
 *     ([RuleAction.Exclude], its reason recorded), the counterparty account (the first
 *     [com.moneymanager.domain.model.rules.AccountSide.COUNTERPARTY] candidate that names one, a built-in
 *     one such as "ATM" consolidating every match; none, the description), whether the counterparty is a
 *     person, whether its user id is throwaway, attributes (a unique-identifier one also identifies the
 *     item), a fee (amount in [amountFormat]; Monzo's `atm_fees_detailed` is [FeeRule.includedInAmount])
 *     and a foreign amount (see [ForeignAmount]).
 *   - An exchange deposit or withdrawal: [RuleAction.Skip] drops it; the direction (unclaimed, the
 *     endpoint's own: a deposit in, a withdrawal out); [RuleAction.LedgerTradeLeg] makes it a leg of
 *     [ledgerTrades] instead of a transfer; the counterparty account, used when no wallet address names
 *     one (unclaimed, the strategy-wide "<account> Funding"); the dedupe id ([RuleAction.ExternalId];
 *     unclaimed, [idField]); and a fee, booked as its own transfer linked by a `fee` relationship, even
 *     on a ledger trade leg. Rules can rewrite a value first ([RuleAction.SetValue]), e.g. to clear an
 *     address that names an internal transfer rather than a wallet.
 */
@Serializable
data class ApiTransactionMappings(
    val amountField: String = "amount",
    val timestampField: String = "created",
    val timestampFormat: TimestampFormat = TimestampFormat.ISO_8601,
    /** Pattern string for [TimestampFormat.PATTERN]; ignored for every other [timestampFormat]. */
    val timestampPattern: String? = null,
    val currencyField: String = "currency",
    val descriptionField: String = "description",
    val amountFormat: ApiAmountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
    val idField: String = "id",
    val counterpartyIdField: String? = null,
    /**
     * Dot-path to a blockchain wallet address on a deposit/withdrawal item (e.g. "address" for a
     * withdrawal destination, "source_address" for a deposit sender). When set and non-blank, the
     * counterparty is modelled as a per-wallet account keyed by this address (so the same wallet
     * reconciles across sources) rather than the generic funding account.
     */
    val counterpartyAddressField: String? = null,
    /** Optional dot-path to the blockchain network of the wallet (e.g. "network_id"), used to label it. */
    val counterpartyNetworkField: String? = null,
    /**
     * Dot-path to the on-chain transaction id (e.g. "txid"). When set, it is stored as a cross-source
     * unique identifier on the transfer so the same on-chain movement seen from another source (another
     * exchange or a wallet import) can be reconciled to it.
     */
    val txidField: String? = null,
    /**
     * Dot-path to a field on this item whose value is looked up against the id index built from any
     * [ApiDataEndpoint.enrichesTransfers] endpoint (e.g. Kraken Ledgers `refid`, matched against
     * DepositStatus/WithdrawStatus `refid`). A hit fills in [counterpartyAddressField]/[txidField]/
     * [counterpartyNetworkField] from the enrichment item. Null disables enrichment for this mapping.
     */
    val joinKeyField: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ledgerTrades: ApiLedgerTrades? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rules: List<StrategyRule> = emptyList(),
)
