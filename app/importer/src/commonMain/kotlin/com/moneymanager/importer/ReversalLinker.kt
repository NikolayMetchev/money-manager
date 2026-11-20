package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.write.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** Links a batch's pass-through spend legs to the earlier movements they reverse (a refund to its charge). */
internal class ReversalLinker(
    private val transactionRepository: TransactionWriteRepository,
    private val relationshipTypeRepository: RelationshipTypeWriteRepository,
) {
    /**
     * Pairs each pass-through row's spend legs with the movements they reverse: per leg, the nearest
     * not-yet-reversed spend leg running in the OPPOSITE direction between the same pair of chain
     * accounts with the same amount, at or before the row's timestamp. An incoming refund thus links
     * each of its legs to the original charge's corresponding leg, a subsequent "refund reversal"
     * (outgoing again) links to the refund it undoes, and so on — each pair consumes one earlier leg (a
     * leg is reversed at most once, enforced against the DB via the query's NOT EXISTS and within this
     * batch via the claimed sets). Candidates come from the DB and from earlier rows in [toImport]; on
     * a timestamp tie the in-batch leg wins (it is the later write). Legs without a match are imported
     * unlinked.
     */
    suspend fun resolveReversalLinks(toImport: List<Classified>): List<Classified> {
        if (toImport.none { it.transfer.passThrough != null }) return toImport
        val reversalTypeId = relationshipTypeRepository.getOrCreate(WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_NAME)
        val claimedExisting = mutableSetOf<TransferId>()
        val claimedInBatch = mutableSetOf<LegKey>()
        val batchSpendLegs = mutableListOf<BatchSpendLeg>()
        val existingCandidates = loadReversalCandidates(toImport, reversalTypeId)
        return toImport.mapIndexed { index, classified ->
            val t = classified.transfer
            val passThrough = t.passThrough ?: return@mapIndexed classified
            // The chain's nodes: conduits (outermost first) then the merchant; leg i moves between
            // nodes[i] and nodes[i+1] (reversed for an incoming refund).
            val nodes = (passThrough.conduits + passThrough.merchantTarget).map { it.requireExistingId() }
            val timestamp = requireNotNull(t.timestamp)
            val amount = passThrough.amount

            val links = mutableMapOf<Int, ReversalLink>()
            for (legIndex in passThrough.conduits.indices) {
                val spendSource = if (passThrough.incoming) nodes[legIndex + 1] else nodes[legIndex]
                val spendTarget = if (passThrough.incoming) nodes[legIndex] else nodes[legIndex + 1]

                // The reversed candidate runs the opposite way: its source/target are this leg's swapped.
                val inBatchMatch =
                    batchSpendLegs
                        .filter { leg ->
                            LegKey(leg.toImportIndex, leg.legIndex) !in claimedInBatch &&
                                leg.source == spendTarget &&
                                leg.target == spendSource &&
                                leg.amount == amount &&
                                leg.timestamp <= timestamp
                        }.maxWithOrNull(compareBy({ it.timestamp }, { it.toImportIndex }))
                val existingMatch =
                    existingCandidates[ReversalCandidateKey(spendTarget, spendSource, amount)]
                        ?.firstOrNull { it.timestamp <= timestamp && it.id !in claimedExisting }

                val link =
                    when {
                        inBatchMatch != null && (existingMatch == null || existingMatch.timestamp <= inBatchMatch.timestamp) -> {
                            claimedInBatch += LegKey(inBatchMatch.toImportIndex, inBatchMatch.legIndex)
                            ReversalLink(ReversalTarget.BatchRow(inBatchMatch.toImportIndex, inBatchMatch.legIndex), reversalTypeId)
                        }

                        existingMatch != null -> {
                            claimedExisting += existingMatch.id
                            ReversalLink(ReversalTarget.Existing(existingMatch.id), reversalTypeId)
                        }

                        else -> {
                            null
                        }
                    }
                if (link != null) links[legIndex] = link
                batchSpendLegs += BatchSpendLeg(index, legIndex, spendSource, spendTarget, amount, timestamp)
            }
            if (links.isEmpty()) classified else classified.copy(reversalLinks = links)
        }
    }

    /**
     * Loads the existing-DB reversal candidates for every pass-through spend leg of [toImport] in one
     * query, grouped by directed (source, target, amount), newest first within each group. Every spend
     * leg has at least one conduit endpoint, so bounding the query by the batch's conduit accounts (and
     * the legs' amounts and max timestamp) covers all per-leg lookups; the currency check the old
     * per-leg query did via asset_id happens here through the [Money] group key. The DB is not written
     * while legs are resolved, so this snapshot is equivalent to querying per leg.
     */
    private suspend fun loadReversalCandidates(
        toImport: List<Classified>,
        reversalTypeId: RelationshipTypeId,
    ): Map<ReversalCandidateKey, List<Transfer>> {
        val conduitAccounts = mutableSetOf<AccountId>()
        val amounts = mutableSetOf<Money>()
        var maxTimestamp: Instant? = null
        for (classified in toImport) {
            val passThrough = classified.transfer.passThrough ?: continue
            passThrough.conduits.forEach { conduitAccounts += it.requireExistingId() }
            amounts += passThrough.amount
            val timestamp = requireNotNull(classified.transfer.timestamp)
            if (maxTimestamp == null || timestamp > maxTimestamp) maxTimestamp = timestamp
        }
        if (conduitAccounts.isEmpty() || maxTimestamp == null) return emptyMap()
        return transactionRepository
            .getUnreversedTransfersTouchingAccounts(conduitAccounts, amounts, maxTimestamp, reversalTypeId)
            .groupBy { ReversalCandidateKey(it.sourceAccountId, it.targetAccountId, it.amount) }
    }

    /** Directed reversal-candidate lookup key: candidates running source → target of exactly amount. */
    private data class ReversalCandidateKey(
        val source: AccountId,
        val target: AccountId,
        val amount: Money,
    )

    /** Identifies one spend leg of one to-import row: (index into the to-import list, leg index). */
    private data class LegKey(
        val toImportIndex: Int,
        val legIndex: Int,
    )

    /** A pass-through spend leg produced by an earlier row in the current to-import list. */
    private class BatchSpendLeg(
        val toImportIndex: Int,
        val legIndex: Int,
        val source: AccountId,
        val target: AccountId,
        val amount: Money,
        val timestamp: Instant,
    )
}
