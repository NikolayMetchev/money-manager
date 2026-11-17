package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.TradeDedupePolicy
import kotlin.time.Instant

/**
 * Matches incoming trades one for one against trades another source already recorded, so one trade described by two
 * sources is booked once.
 *
 * The exact-tuple match the writer performs cannot see these: an export stamps a trade to the second where an API
 * stamps it to the millisecond. A match **suppresses the write** and reports the existing trade's id, as the writer's
 * own idempotency does, so the source row surfaces as a duplicate pointing at the trade it duplicates.
 *
 * Aggregation (one source's gross trade against the fills another reports for it) is not decided here: both are
 * booked, and the matcher's trade-aggregation stage excludes the gross trade by its fills (`MatchSync`), whichever
 * source came first.
 *
 * Matched trades are **claimed**, so two incoming trades never both match the same existing one.
 */
class TradeReconciler(
    private val policy: TradeDedupePolicy.Fuzzy,
    existing: List<Trade>,
) {
    private data class BucketKey(
        val fromAccountId: AccountId,
        val toAccountId: AccountId,
        val fromAssetId: AssetId,
        val toAssetId: AssetId,
    )

    /** Candidates grouped by the fields any match requires, then ordered by time within a bucket. */
    private val buckets: Map<BucketKey, List<Trade>> =
        existing
            .groupBy { BucketKey(it.fromAccountId, it.toAccountId, it.from.asset.id, it.to.asset.id) }
            .mapValues { (_, trades) -> trades.sortedBy { it.timestamp } }

    private val claimed = mutableSetOf<TradeId>()

    /** The id of an existing trade that [intent] duplicates, or null when nothing matches and the trade should be written. */
    fun match(intent: ImportTradeIntent): TradeId? {
        val fromAccountId = intent.fromAccountId ?: return null
        val toAccountId = intent.toAccountId ?: return null
        val fromAmount = intent.fromAmount ?: return null
        val toAmount = intent.toAmount ?: return null
        val timestamp = intent.timestamp ?: return null

        // The overwhelmingly common case is a movement no other source recorded: one hash miss and out,
        // before any window arithmetic.
        val candidates = buckets[BucketKey(fromAccountId, toAccountId, fromAmount.asset.id, toAmount.asset.id)] ?: return null
        val match =
            candidates
                .filter { it.id !in claimed && (it.timestamp - timestamp).absoluteValue <= policy.window }
                .filter { it.from == fromAmount && (policy.matchFromLegOnly || it.to == toAmount) }
                .minByOrNull { (it.timestamp - timestamp).absoluteValue } ?: return null
        claimed += match.id
        return match.id
    }
}
