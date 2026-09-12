package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportTradeIntent
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
 * Matched legs are **claimed**, so two incoming trades never both match the same one. Claims live for
 * one reconciler (one import), exactly as in [TradeReconciler]: across separate imports a leg could
 * be claimed twice, which is the safe direction — over-suppression cannot invent money — and amount
 * equality bounds the other.
 */
class ConversionTradeReconciler(
    private val window: Duration,
    legs: List<ExistingConversionLeg>,
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

    private val byDebitLeg: Map<LegKey, List<ExistingConversionLeg>> = legs.groupBy { keyOf(it) }

    private val claimed = mutableSetOf<TransferId>()

    /**
     * The debit leg of the conversion [intent] duplicates, or null when nothing matches and the trade
     * should be written. On a match the leg is claimed.
     */
    fun match(intent: ImportTradeIntent): TransferId? {
        val accountId = intent.fromAccountId ?: return null
        val fromAmount = intent.fromAmount ?: return null
        val toAmount = intent.toAmount ?: return null
        val timestamp = intent.timestamp ?: return null

        val key = LegKey(accountId, fromAmount.asset.id, fromAmount.amount.toString(), toAmount.asset.id)
        val leg =
            byDebitLeg[key]
                ?.firstOrNull { it.debitTransferId !in claimed && (it.timestamp - timestamp).absoluteValue <= window }
                ?: return null
        claimed += leg.debitTransferId
        return leg.debitTransferId
    }

    private fun keyOf(leg: ExistingConversionLeg): LegKey =
        LegKey(leg.accountId, leg.amount.asset.id, leg.amount.amount.toString(), leg.creditAssetId)
}
