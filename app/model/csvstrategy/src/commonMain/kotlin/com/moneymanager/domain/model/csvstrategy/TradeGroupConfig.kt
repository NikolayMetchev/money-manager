package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Declares how a CSV source expresses a **trade** that arrives as several separate rows sharing one
 * timestamp — one row per partial fill per leg — rather than as a single cross-asset row.
 *
 * This is the trade-shaped sibling of [ConversionConfig], and a strategy may declare both. They solve
 * different problems:
 *  - [ConversionConfig] handles conversions whose legs cannot be attributed to each other (a
 *    many-assets-in / one-asset-out dust sweep, where no column says which credited amount came from
 *    which debited asset). It keeps every leg a single-asset transfer routed through a shared
 *    conversion account, so balances stay exact without inventing a pairing.
 *  - [TradeGroupConfig] handles the case where a group's legs *are* attributable: every debit row in
 *    the group names one asset and every credit row names one other asset, so their sums are exactly
 *    the two sides of one conversion and can be booked as a real `trade` on the owner account.
 *
 * When set on a [CsvImportStrategy], the importer buckets matching rows by timestamp (widened by
 * [groupingWindowSeconds]), and for each bucket whose debits name exactly one asset and whose credits
 * name exactly one other asset emits a single trade — owner account on both sides, debit sum out,
 * credit sum in. Fee rows in the bucket become their own transfers to [feeAccountName], because a
 * `trade` row carries no fee field. A bucket that does not resolve — no credits, an empty side, or
 * more than one asset on a side — is left alone and its rows import as ordinary transfers to whatever
 * account the strategy's mappings chose, so no row is ever dropped and the residue is visible.
 *
 * A `trade` row carries no fee field, so fee rows are deliberately **not** part of this config: leave
 * them out of both patterns and let the strategy's ordinary account routing book them as their own
 * transfer to a fee account (naming the account another source already uses keeps one balance).
 *
 * Both patterns are matched with [Regex.containsMatchIn], so anchor them (`^…$`) unless a prefix
 * match is genuinely wanted — an unanchored `Buy` would also claim `Transaction Buy`.
 *
 * @property signalColumn Column examined to classify a row as a trade leg (e.g. "Operation").
 * @property debitPattern Regex identifying a DEBIT leg — the asset leaving the owner account.
 * @property creditPattern Regex identifying a CREDIT leg — the asset received into the owner account.
 * @property sideAmountColumn Optional column whose sign decides the side, for sources that use one
 *                            [signalColumn] value on both legs (Binance labels both sides of an older
 *                            fill `Transaction Related`). When set, the two patterns together only say
 *                            which rows are legs at all, and a leg is a DEBIT when this column parses
 *                            negative and a CREDIT when positive; a row parsing to zero or unparseably
 *                            is not a leg. When null the patterns alone decide, debit tested first.
 * @property groupingWindowSeconds Seconds of timestamp jitter tolerated between consecutive rows of
 *                                 one group. Zero when the source stamps every leg of a fill with the
 *                                 identical time.
 * @property descriptionTemplate Description given to the assembled trade. `{from}` and `{to}` are
 *                              substituted with the debited and credited asset codes. Cosmetic only:
 *                              a trade's identity never includes its description.
 * @property reconcileWindowSeconds When set, an assembled trade that another source already recorded —
 *                                  as one trade, or as the individual fills this group aggregates — is
 *                                  not booked again. Deliberately **not** the strategy's
 *                                  `crossSourceReconcileWindowSeconds`: that window has to be wide
 *                                  enough for a bank's settlement lag, and a wide window here would
 *                                  pull a later order's fills into the candidate set and stop the sums
 *                                  matching at all. Sources disagree about a trade's instant only by
 *                                  sub-second rounding, so keep this to a few seconds. Null disables it.
 */
@Serializable
data class TradeGroupConfig(
    val signalColumn: String,
    val debitPattern: String,
    val creditPattern: String,
    val sideAmountColumn: String? = null,
    val groupingWindowSeconds: Long = 0,
    val descriptionTemplate: String = "Buy {to}/{from}",
    val reconcileWindowSeconds: Long? = null,
) {
    init {
        require(groupingWindowSeconds >= 0) { "TradeGroupConfig.groupingWindowSeconds must not be negative" }
    }
}
