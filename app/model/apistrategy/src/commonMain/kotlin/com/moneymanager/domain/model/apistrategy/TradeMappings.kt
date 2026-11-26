package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** How a trading pair symbol is split into its base and quote assets. */
@Serializable
enum class InstrumentSplitMode {
    /** Split on a separator character (Crypto.com "BTC_USD"). */
    SEPARATOR,

    /** Explicit base/quote fields on the item (some order endpoints). */
    EXPLICIT_FIELDS,

    /** Strip a known quote-asset suffix from a separator-less symbol (Binance "BTCUSDT"). */
    QUOTE_SUFFIX,
}

/**
 * Field mappings for a trade (fill) endpoint. A fill exchanges a base asset for a quote asset. The
 * engine derives two [com.moneymanager.domain.model.Money] legs: base = [baseQuantityField] of the base
 * asset; quote = [quoteQuantityField] (or [baseQuantityField] × [priceField]) of the quote asset. For a
 * BUY the quote leg leaves and the base leg arrives; a SELL reverses them. Both legs sit on the single
 * exchange account, so the movement is a cross-asset [com.moneymanager.domain.model.Trade].
 *
 * @property instrumentField Dot-path to the pair symbol (e.g. "instrument_name"); unused (null) with
 *   [InstrumentSplitMode.EXPLICIT_FIELDS].
 * @property splitMode How [instrumentField] is split into base/quote assets.
 * @property instrumentSeparator Separator for [InstrumentSplitMode.SEPARATOR] (default "_").
 * @property baseAssetField/quoteAssetField Dot-paths for [InstrumentSplitMode.EXPLICIT_FIELDS].
 * @property quoteAssets Known quote assets for [InstrumentSplitMode.QUOTE_SUFFIX] (longest match wins).
 * @property baseQuantityField Dot-path to the base-asset quantity traded.
 * @property priceField Dot-path to the price (quote per base); quote amount = quantity × price.
 * @property quoteQuantityField Dot-path to an explicit quote amount (used instead of price when present).
 * @property timestampField/timestampFormat Dot-path + encoding of the fill timestamp.
 * @property idField Dot-path to the stable trade id (used for de-duplication).
 * @property orderIdField Optional dot-path to the owning order id (joins ORDERS metadata onto the trade).
 * @property rules What a trade or order item decides, in order (first claim wins): [RuleAction.Skip] drops
 *   it; [RuleAction.TradeSide] is its side (unclaimed, the item is dropped); [RuleAction.ExternalId] its
 *   dedupe id (unclaimed, [idField]); [RuleAction.Fee] its fee, whose asset defaults to the quote asset;
 *   and [RuleAction.SetValue] supplies a value the item never carries (Binance dust always credits BNB).
 */
@Serializable
data class ApiTradeMappings(
    val instrumentField: String? = null,
    val splitMode: InstrumentSplitMode = InstrumentSplitMode.SEPARATOR,
    val instrumentSeparator: String = "_",
    val baseAssetField: String? = null,
    val quoteAssetField: String? = null,
    @Serializable(with = SortedStringListSerializer::class)
    val quoteAssets: List<String> = emptyList(),
    val baseQuantityField: String,
    val priceField: String? = null,
    val quoteQuantityField: String? = null,
    val timestampField: String,
    val timestampFormat: TimestampFormat = TimestampFormat.EPOCH_MS,
    /** Pattern string for [TimestampFormat.PATTERN]; ignored for every other [timestampFormat]. */
    val timestampPattern: String? = null,
    val idField: String,
    val orderIdField: String? = null,
    val descriptionField: String? = null,
    /** Order-only fields surfaced as trade attributes when ORDERS metadata is joined in. */
    val orderTypeField: String? = null,
    val orderStatusField: String? = null,
    // Order-only fields for persisting the order itself (ORDERS endpoints; ignored for TRADES).
    val limitPriceField: String? = null,
    val avgPriceField: String? = null,
    val updateTimestampField: String? = null,
    val clientOidField: String? = null,
    val timeInForceField: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rules: List<StrategyRule> = emptyList(),
)
