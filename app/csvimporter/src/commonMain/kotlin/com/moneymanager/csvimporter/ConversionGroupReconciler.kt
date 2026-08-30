package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.TradeReadRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Decides which asset-conversion groups the database already holds as trades, so a sweep another
 * source recorded is not counted twice.
 *
 * A conversion group is emitted as transfers, not a trade, precisely because its credited amounts
 * cannot be attributed to its debited assets (see `ConversionConfig`). So it cannot be matched by the
 * engine's trade reconciler, and it has to be matched **as a whole**: suppressing only the debit legs
 * would leave the credits behind, double-counting the received asset and stranding a balance in the
 * conversion account.
 *
 * The debited legs are what makes a match possible. A source that reports the conversion as a trade
 * (Binance's dust API) agrees with the export exactly on the asset given up, and disagrees on the
 * asset received — the API reports it gross and the export net of the service charge. So a group
 * matches when **every** one of its debit legs finds a distinct existing trade with the same account,
 * the same debited asset and the same debited amount inside the window; the credited side is never
 * compared, because it cannot be.
 */
class ConversionGroupReconciler(
    private val window: Duration,
    existing: List<Trade>,
) {
    private data class LegKey(
        val accountId: Long,
        val assetId: Long,
        val amount: String,
    )

    private val byDebitLeg: Map<LegKey, List<Trade>> =
        existing.groupBy { LegKey(it.fromAccountId.id, it.from.asset.id.id, it.from.amount.toString()) }

    private val claimed = mutableSetOf<TradeId>()

    /**
     * The trades this group duplicates, or null when it is not already recorded and must be imported.
     * On a match every trade involved is claimed, so two groups never both match the same one.
     */
    fun match(debits: List<CsvTransferWithAttributes>): List<TradeId>? {
        if (debits.isEmpty()) return null
        val matched = mutableListOf<TradeId>()
        val claimedHere = mutableSetOf<TradeId>()
        for (debit in debits) {
            val key =
                LegKey(
                    debit.transfer.sourceAccountId.id,
                    debit.transfer.amount.asset.id.id,
                    debit.transfer.amount.amount
                        .toString(),
                )
            val candidate =
                byDebitLeg[key]
                    ?.firstOrNull {
                        it.id !in claimed &&
                            it.id !in claimedHere &&
                            (it.timestamp - debit.transfer.timestamp).absoluteValue <= window
                    }
                    // One unmatched leg means the group is not the one already recorded, so the whole
                    // group imports. Partial suppression would corrupt the balances it is meant to protect.
                    ?: return null
            claimedHere += candidate.id
            matched += candidate.id
        }
        claimed += claimedHere
        return matched
    }
}

/**
 * Row index -> the trade it duplicates, for every leg of every conversion group another source already
 * recorded. Empty unless the strategy declares both a conversion config and a reconcile window and a
 * [TradeReadRepository] is available — reconciliation is opt-in, exactly like the transfer path's.
 */
suspend fun reconcileConversionGroups(
    strategy: CsvImportStrategy,
    rows: List<CsvTransferWithAttributes>,
    tradeRepository: TradeReadRepository?,
): Map<Long, TradeId> {
    val conversionConfig = strategy.conversionConfig ?: return emptyMap()
    val window = strategy.crossSourceReconcileWindowSeconds?.seconds ?: return emptyMap()
    val repository = tradeRepository ?: return emptyMap()

    val groups = conversionGroups(rows, conversionConfig.pairingWindowSeconds.seconds)
    if (groups.isEmpty()) return emptyMap()

    val legs = groups.flatten()
    val accountIds =
        legs.flatMapTo(mutableSetOf()) { listOf(it.transfer.sourceAccountId, it.transfer.targetAccountId) }
    val existing =
        repository.getTradesByAccountsAndDateRange(
            accountIds = accountIds,
            minTimestamp = legs.minOf { it.transfer.timestamp } - window,
            maxTimestamp = legs.maxOf { it.transfer.timestamp } + window,
        )
    if (existing.isEmpty()) return emptyMap()

    val reconciler = ConversionGroupReconciler(window, existing)
    val reconciled = mutableMapOf<Long, TradeId>()
    for (group in groups) {
        val debits = group.filter { it.conversionLeg?.side == ConversionSide.DEBIT }
        val matched = reconciler.match(debits) ?: continue
        // Every leg of the group - debits and the credits paired with them - records the trade it
        // duplicates, so the rows read as duplicates of something rather than as silently missing.
        group.forEach { reconciled[it.rowIndex] = matched.first() }
    }
    return reconciled
}

/**
 * Groups a file's conversion legs into events: each debit paired with the credits nearest it in time,
 * mirroring how the applier links them. Returns one entry per debit-bearing event, with every leg that
 * belongs to it, so a caller can accept or reject the event as a unit.
 */
fun conversionGroups(
    rows: List<CsvTransferWithAttributes>,
    pairingWindow: Duration,
): List<List<CsvTransferWithAttributes>> {
    val legs =
        rows
            .filter { it.conversionLeg != null }
            .sortedWith(compareBy({ it.transfer.timestamp }, { it.rowIndex }))
    if (legs.isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<CsvTransferWithAttributes>>()
    var current = mutableListOf(legs.first())
    for (leg in legs.drop(1)) {
        val previous = current.last().transfer.timestamp
        if (leg.transfer.timestamp - previous <= pairingWindow) {
            current += leg
        } else {
            groups += current
            current = mutableListOf(leg)
        }
    }
    groups += current
    return groups
}
