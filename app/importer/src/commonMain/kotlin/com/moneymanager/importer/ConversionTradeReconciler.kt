package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.LocalTradeKey
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One conversion the database already holds: the debit leg of an asset conversion, plus the asset the
 * credit leg it is linked to received.
 *
 * @property debitTransferId The debit leg — `id1` of the conversion relationship.
 * @property accountId The account the asset left (the debit leg's source account).
 * @property amount The asset and amount given up.
 * @property timestamp When the source stamped the debit leg.
 * @property creditAssetId The asset the paired credit leg (`id2`) received. Its *amount* is
 *                         deliberately not carried: it cannot be compared (see
 *                         [ConversionTradeReconciler]).
 */
data class ExistingConversionLeg(
    val debitTransferId: TransferId,
    val accountId: AccountId,
    val amount: Money,
    val timestamp: Instant,
    val creditAssetId: AssetId,
)

/**
 * Matches incoming trades against conversions another source already recorded as **transfers**, so
 * one movement described by two sources is booked once.
 *
 * This is the mirror of `ConversionGroupReconciler` on the CSV side. A source that cannot say which
 * credit came from which debit books a conversion as linked debit/credit transfer legs through a
 * shared conversion account (see `ConversionConfig`); a source that does know the attribution — a
 * dust-sweep endpoint, say — books the same event as trades. Whichever arrives second must not book
 * it again, and [TradeReconciler] cannot see this case: it compares trades against trades.
 *
 * Only the **debited** side is compared. The two sources agree exactly on the asset given up, and
 * disagree on the asset received — one reports it gross where the other reports it net of the
 * source's service charge. So the credit leg contributes its asset and nothing else.
 *
 * A match **suppresses the write**, which is the only outcome available: a trade can carry neither an
 * `excluded` attribute nor a `reconciled` relationship, since both tables reference `transfer(id)`.
 * Unlike the trade-against-trade case there is not even an existing trade id to report, so the
 * suppressed key surfaces via `ImportResult.conversionReconciledTradeKeys` instead.
 *
 * The whole batch is assigned **up front**, which is why [incoming] is handed over to the constructor
 * rather than resolved a trade at a time. Taking each trade's nearest free leg as it arrives would
 * make the outcome depend on the order the batch happens to be in: two legs one window apart and two
 * trades between them can be paired up completely, yet a trade arriving first can take the leg the
 * other one needs and leave itself the only candidate for none. Assigning together avoids that, and
 * the assignment is **maximal** — it matches as many trades as any assignment could (see
 * [assign]).
 *
 * A leg is used at most once, but only within one reconciler (one import) — across separate imports a
 * leg could be matched twice, exactly as in [TradeReconciler]. That is the safe direction:
 * over-suppression cannot invent money, and amount equality bounds the other.
 */
class ConversionTradeReconciler(
    private val window: Duration,
    legs: List<ExistingConversionLeg>,
    incoming: List<ImportTradeIntent> = emptyList(),
) {
    /**
     * Every field a match requires. The amount is keyed as a string for the same reason
     * `ConversionGroupReconciler` does: it is an exact value, not a range, and the string is the
     * canonical form both sides derive from.
     */
    private data class LegKey(
        val accountId: AccountId,
        val assetId: AssetId,
        val amount: String,
        val creditAssetId: AssetId,
    )

    /** One incoming trade with every field a match needs resolved, or null when it is not comparable. */
    private data class IncomingTrade(
        val key: LocalTradeKey,
        val legKey: LegKey,
        val timestamp: Instant,
    )

    private val assignments: Map<LocalTradeKey, TransferId> = assign(legs, incoming)

    /**
     * The debit leg of the conversion [intent] duplicates, or null when nothing matches and the trade
     * should be written.
     */
    fun match(intent: ImportTradeIntent): TransferId? = assignments[intent.key]

    /**
     * Pairs trades to legs one [LegKey] group at a time, each group's trades in timestamp order, each
     * taking the **earliest** free leg still inside its window.
     *
     * Earliest rather than nearest is what makes the result maximal. A trade's candidate legs are the
     * ones inside one window of it, so in timestamp order the candidate sets only ever slide forward:
     * a leg one trade skips is a leg no later trade can use either, while the leg it takes is the one
     * with the least value left to the trades behind it. Taking the nearest instead can strand a
     * later trade whose own window covers that leg alone.
     */
    private fun assign(
        legs: List<ExistingConversionLeg>,
        incoming: List<ImportTradeIntent>,
    ): Map<LocalTradeKey, TransferId> {
        if (legs.isEmpty() || incoming.isEmpty()) return emptyMap()
        val legsByKey = legs.groupBy(::keyOf).mapValues { (_, group) -> group.sortedBy { it.timestamp } }
        val assignments = mutableMapOf<LocalTradeKey, TransferId>()
        for ((legKey, trades) in incoming.mapNotNull(::tradeOf).groupBy { it.legKey }) {
            val candidates = legsByKey[legKey] ?: continue
            val taken = mutableSetOf<TransferId>()
            for (trade in trades.sortedBy { it.timestamp }) {
                val leg =
                    candidates.firstOrNull {
                        it.debitTransferId !in taken && (it.timestamp - trade.timestamp).absoluteValue <= window
                    } ?: continue
                taken += leg.debitTransferId
                assignments[trade.key] = leg.debitTransferId
            }
        }
        return assignments
    }

    private fun tradeOf(intent: ImportTradeIntent): IncomingTrade? {
        val accountId = intent.fromAccountId ?: return null
        val fromAmount = intent.fromAmount ?: return null
        val toAmount = intent.toAmount ?: return null
        val timestamp = intent.timestamp ?: return null
        return IncomingTrade(
            key = intent.key,
            legKey = LegKey(accountId, fromAmount.asset.id, fromAmount.amount.toString(), toAmount.asset.id),
            timestamp = timestamp,
        )
    }

    private fun keyOf(leg: ExistingConversionLeg): LegKey =
        LegKey(leg.accountId, leg.amount.asset.id, leg.amount.amount.toString(), leg.creditAssetId)
}
