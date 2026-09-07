package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csvstrategy.TradeGroupConfig
import com.moneymanager.importengineapi.LocalTradeKey
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Prefix of the [LocalTradeKey] a CSV row (or row group) produces. The remainder is the import id and
 * the row index, so a key decodes back to the row whose outcome must be written.
 */
const val CSV_TRADE_KEY_PREFIX = "csv-"

/**
 * Rows a source split across one trade, bucketed by timestamp (see [TradeGroupConfig]). A group holds
 * only rows the mapper flagged as trade legs; fee rows are deliberately not part of it.
 */
data class TradeGroup(
    val debits: List<CsvTransferWithAttributes>,
    val credits: List<CsvTransferWithAttributes>,
) {
    val rows: List<CsvTransferWithAttributes> get() = debits + credits

    /** Row indexes of every leg, for writing the assembled trade's outcome back to all of them. */
    val rowIndexes: List<Long> get() = rows.map { it.rowIndex }
}

/**
 * A [TradeGroup] that resolved into a single trade: one asset out, one other asset in, both on the
 * same owner account.
 *
 * @property timestamp The group's earliest row timestamp, so re-imports and different files that
 *                     contain the same event agree on one instant.
 */
data class AssembledTrade(
    val group: TradeGroup,
    val ownerAccountId: AccountId,
    val timestamp: Instant,
    val fromAmount: Money,
    val toAmount: Money,
    val description: String,
)

/**
 * The batch key for this assembled trade. Keyed on the group's lowest row index so the key is stable
 * across re-imports of the same file and decodes back to a real row for status write-back.
 */
fun AssembledTrade.tradeKey(csvImportId: CsvImportId): LocalTradeKey =
    LocalTradeKey("$CSV_TRADE_KEY_PREFIX${csvImportId.id}-${group.rowIndexes.min()}")

/**
 * Splits [rows] into trade groups. Rows the mapper did not flag as a trade leg are ignored; the caller
 * still imports them as ordinary transfers.
 *
 * Grouping is a greedy chain over time: a leg joins the current group while it is within
 * [TradeGroupConfig.groupingWindowSeconds] of the group's **last** leg, so a group whose legs straddle
 * a second boundary still holds together while genuinely separate events (minutes or hours apart) stay
 * separate. A zero window means legs must share the exact instant.
 */
fun groupTradeLegs(
    rows: List<CsvTransferWithAttributes>,
    config: TradeGroupConfig,
): List<TradeGroup> {
    val legs =
        rows
            .filter { it.tradeLeg != null }
            .sortedWith(compareBy({ it.transfer.timestamp }, { it.rowIndex }))
    if (legs.isEmpty()) return emptyList()

    val window = config.groupingWindowSeconds.seconds
    val groups = mutableListOf<MutableList<CsvTransferWithAttributes>>()
    var current = mutableListOf(legs.first())
    for (leg in legs.drop(1)) {
        val previous = current.last().transfer.timestamp
        if (leg.transfer.timestamp - previous <= window) {
            current += leg
        } else {
            groups += current
            current = mutableListOf(leg)
        }
    }
    groups += current

    return groups.map { group ->
        TradeGroup(
            debits = group.filter { it.tradeLeg?.side == TradeLegSide.DEBIT },
            credits = group.filter { it.tradeLeg?.side == TradeLegSide.CREDIT },
        )
    }
}

/**
 * Folds a group into one trade, or returns null when it does not resolve — no legs on a side, more
 * than one asset on a side, a zero total, or legs that disagree about which account they belong to.
 * A null is not an error: the caller leaves the group's rows to import as ordinary transfers, so the
 * residue lands somewhere visible instead of being silently reshaped or dropped.
 *
 * The owner account is read off the legs themselves: with the usual `flipAccountsOnPositive` mapping a
 * debit leg has the owner as its source and a credit leg has it as its target.
 */
@Suppress("ReturnCount")
fun TradeGroup.assemble(config: TradeGroupConfig): AssembledTrade? {
    if (debits.isEmpty() || credits.isEmpty()) return null

    val ownerAccountIds =
        (debits.map { it.transfer.sourceAccountId } + credits.map { it.transfer.targetAccountId }).toSet()
    val ownerAccountId = ownerAccountIds.singleOrNull() ?: return null

    val fromAsset = debits.map { it.transfer.amount.asset }.distinctBy { it.id }.singleOrNull() ?: return null
    val toAsset = credits.map { it.transfer.amount.asset }.distinctBy { it.id }.singleOrNull() ?: return null
    if (fromAsset.id == toAsset.id) return null

    // Leg amounts are already absolute (the mapper takes abs() and encodes direction in the accounts),
    // so summing each side gives the two totals of the one conversion the group describes.
    val fromAmount = debits.map { it.transfer.amount }.reduce(Money::plus)
    val toAmount = credits.map { it.transfer.amount }.reduce(Money::plus)
    if (fromAmount.amount == BigInteger.ZERO || toAmount.amount == BigInteger.ZERO) return null

    return AssembledTrade(
        group = this,
        ownerAccountId = ownerAccountId,
        timestamp = rows.minOf { it.transfer.timestamp },
        fromAmount = fromAmount,
        toAmount = toAmount,
        description =
            config.descriptionTemplate
                .replace("{from}", fromAsset.code)
                .replace("{to}", toAsset.code),
    )
}
