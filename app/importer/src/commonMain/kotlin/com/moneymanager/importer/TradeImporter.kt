package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.repository.write.ExchangeOrderWriteRepository
import com.moneymanager.domain.repository.write.NewTrade
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.domain.repository.write.TradeEdit
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.TradeDedupePolicy
import com.moneymanager.importengineapi.observationContent
import com.moneymanager.importengineapi.observationKey
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * The trades a batch books: the ones it creates (unless another source already booked the same conversion), the ones a
 * person edits, the orders that group them and the conversion trades of the transfers it books.
 */
internal class TradeImporter(
    private val tradeRepository: TradeWriteRepository,
    private val exchangeOrderRepository: ExchangeOrderWriteRepository,
    private val transactionRepository: TransactionWriteRepository,
    private val relationshipRepository: RelationshipWriteRepository,
    private val relationshipTypeRepository: RelationshipTypeWriteRepository,
    private val observationRepository: ObservationWriteRepository,
) {
    /** The trade-related portion of an [ImportResult], produced by [importTrades]. */
    class TradeOutcome(
        val createdTradeIds: Map<LocalTradeKey, TradeId>,
        val dedupedTradeKeys: Set<LocalTradeKey>,
    )

    /** Creates the batch's CREATE trades, with what their records [observer] says, then upserts its orders and links their trades. */
    suspend fun importTrades(
        batch: ImportBatch,
        observer: SourceObservations,
    ): TradeOutcome {
        val createdTradeIds = mutableMapOf<LocalTradeKey, TradeId>()
        val dedupedTradeKeys = mutableSetOf<LocalTradeKey>()

        val occurrences = TradeOccurrences()
        // Cross-source reconcile (opt-in): two sources describing one conversion rarely agree on the
        // exact tuple the writer matches on, so load the window they could disagree within and match in
        // memory. A hit suppresses the write and reports the existing trade, which is the only way a
        // trade can be linked to its duplicate (see TradeDedupePolicy).
        val tradeReconciler = buildTradeReconciler(batch)
        // The same movement again, but recorded by the other source as *transfers*: a conversion whose
        // credits it could not attribute to its debits. There is no trade id to report for such a
        // match, so the order links resolved below would have nothing to point at - hence trades an
        // order claims are held back from it. A conversion is never part of an order.
        val orderLinkedTradeKeys = batch.orders.flatMapTo(mutableSetOf()) { it.tradeKeys }
        val conversionReconciler = buildConversionTradeReconciler(batch, orderLinkedTradeKeys)
        val tradesToWrite = mutableListOf<Pair<ImportTradeIntent, NewTrade>>()
        for (intent in batch.trades.creates()) {
            // Either kind of cross-source match suppresses the write; they differ only in what can be
            // reported, so they are resolved together and the write is skipped once.
            val reconciledId = tradeReconciler?.match(intent)
            val reconciledConversionLeg =
                if (reconciledId == null) conversionReconciler?.match(intent) else null
            if (reconciledId != null || reconciledConversionLeg != null) {
                if (reconciledId != null) {
                    createdTradeIds[intent.key] = reconciledId
                    dedupedTradeKeys += intent.key
                }
                continue
            }
            val trade =
                NewTrade(
                    timestamp = requireNotNull(intent.timestamp),
                    description = requireNotNull(intent.description),
                    fromAccountId = requireNotNull(intent.fromAccountId),
                    fromAmount = requireNotNull(intent.fromAmount),
                    toAccountId = requireNotNull(intent.toAccountId),
                    toAmount = requireNotNull(intent.toAmount),
                    source = intent.source,
                    attributes = intent.attributes,
                    reuseId = observer.reuse(intent.observationKey(), TransactionKind.TRADE) as TradeId?,
                )
            tradesToWrite += intent to trade.copy(occurrence = occurrences.next(trade))
        }
        val tradeResults =
            if (tradesToWrite.isEmpty()) emptyList() else tradeRepository.createTrades(tradesToWrite.map { it.second })
        for ((toWrite, tradeResult) in tradesToWrite.zip(tradeResults)) {
            val (intent, written) = toWrite
            // A re-imported trade loses the attributes its source owns but no longer reports (e.g. the
            // `excluded` of a trade the source has since un-deleted), like a transfer UPDATE; so does one a re-run takes back.
            if (!tradeResult.created || written.reuseId != null) {
                val reported = intent.attributes.mapTo(mutableSetOf()) { it.typeId }
                val stale = intent.ownedAttributes.filterKeys { it !in reported }
                if (stale.isNotEmpty()) {
                    tradeRepository
                        .getAttributes(tradeResult.id)
                        .first()
                        .filter { attr ->
                            stale.containsKey(attr.attributeType.id) &&
                                stale[attr.attributeType.id].let { it == null || attr.value in it }
                        }.forEach { tradeRepository.removeAttribute(tradeResult.id, it.attributeType.id) }
                }
            }
            createdTradeIds[intent.key] = tradeResult.id
            if (!tradeResult.created) dedupedTradeKeys += intent.key
        }
        // A record observes the trade it lands on, whether it created it or found it again (an overlapping session's
        // fill): unimporting the record that created it must not take it while another still says it.
        observer.recordAll(
            batch.trades.mapNotNull { intent ->
                val tradeId = createdTradeIds[intent.key] ?: return@mapNotNull null
                SourceBooking(intent.observationKey(), intent.source, tradeId) { intent.observationContent() }
            },
        )
        for (intent in batch.orders) {
            val orderResult =
                exchangeOrderRepository.upsertOrder(
                    accountId = intent.accountId,
                    orderRef = intent.orderRef,
                    clientOid = intent.clientOid,
                    side = intent.side,
                    orderType = intent.orderType,
                    timeInForce = intent.timeInForce,
                    status = intent.status,
                    limitPrice = intent.limitPrice,
                    quantity = intent.quantity,
                    avgPrice = intent.avgPrice,
                    createdAt = intent.createdAt,
                    updatedAt = intent.updatedAt,
                    source = intent.source,
                )
            for (tradeKey in intent.tradeKeys) {
                // A dangling key means the producer's trade/order keys diverged — losing the link
                // silently would be far worse than failing the import.
                val tradeId =
                    requireNotNull(createdTradeIds[tradeKey]) {
                        "Order ${intent.orderRef} references trade key '${tradeKey.value}' absent from this batch"
                    }
                exchangeOrderRepository.linkTrade(orderResult.id, tradeId)
            }
        }
        return TradeOutcome(createdTradeIds, dedupedTradeKeys)
    }

    /** Applies the batch's UPDATE trades. */
    suspend fun applyUpdates(batch: ImportBatch) {
        tradeRepository.updateTrades(
            batch.trades.updates().map { intent ->
                TradeEdit(
                    trade =
                        Trade(
                            id = requireNotNull(intent.existingId),
                            timestamp = requireNotNull(intent.timestamp),
                            description = requireNotNull(intent.description),
                            fromAccountId = requireNotNull(intent.fromAccountId),
                            from = requireNotNull(intent.fromAmount),
                            toAccountId = requireNotNull(intent.toAccountId),
                            to = requireNotNull(intent.toAmount),
                        ),
                    source = intent.source,
                )
            },
        )
    }

    /**
     * Books each created transfer's [conversion][ImportTransfer.conversion] as a trade on its own account: settled → foreign for
     * a payment, foreign → settled for a refund. Only created transfers get one (a DUPLICATE's trade already
     * exists), and a trade is excluded with its transfer, whether the source excluded the row or a
     * reconcile did — otherwise the conversion of money that was never spent would still move balances.
     * Identical conversions within the batch get distinct occurrences, as the trade writer requires.
     */
    suspend fun writeConversionTrades(
        toImport: List<Classified>,
        observer: SourceObservations,
    ) {
        val occurrences = TradeOccurrences()
        val expanded = toImport.mapNotNull { ObservationExpander.expandConversion(it.transfer) }
        val trades =
            expanded.map { trade ->
                val conversion =
                    NewTrade(
                        timestamp = trade.timestamp,
                        description = trade.description,
                        fromAccountId = trade.accountId,
                        fromAmount = trade.fromAmount,
                        toAccountId = trade.accountId,
                        toAmount = trade.toAmount,
                        source = trade.provenance,
                        attributes = trade.attributes,
                        reuseId = observer.reuse(trade.key, TransactionKind.TRADE) as TradeId?,
                    )
                conversion.copy(occurrence = occurrences.next(conversion))
            }
        if (trades.isEmpty()) return
        val results = tradeRepository.createTrades(trades)
        for ((index, result) in results.withIndex()) {
            val trade = expanded[index]
            if (result.created) {
                observer.record(trade.key, trade.provenance, result.id) { trade.observationContent() }
            } else {
                observer.reclaim(trade.key, result.id) { trade.observationContent() }
            }
        }
    }

    /**
     * A [TradeReconciler] over the trades the batch's own trades could be duplicating, or null when the
     * batch opts out, has no trades, or nothing exists to match. The window is loaded once and widened
     * by the policy's tolerance, mirroring [ExistingTransferLoader.loadExisting]'s slack: an existing trade stamped just
     * outside the batch's own span is exactly the one a second-vs-millisecond disagreement produces.
     */
    private suspend fun buildTradeReconciler(batch: ImportBatch): TradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val creates = batch.trades.creates().filter { it.timestamp != null }
        if (creates.isEmpty()) return null
        val accountIds = creates.flatMap { listOfNotNull(it.fromAccountId, it.toAccountId) }.toSet()
        if (accountIds.isEmpty()) return null
        val minTs = creates.minOf { requireNotNull(it.timestamp) } - policy.window
        val maxTs = creates.maxOf { requireNotNull(it.timestamp) } + policy.window
        val loaded = tradeRepository.getTradesByAccountsAndDateRange(accountIds, minTs, maxTs)
        val vacant = observationRepository.getVacantTransactionIds(loaded.map { it.id })
        val existing = loaded.filterNot { it.id in vacant }
        return if (existing.isEmpty()) null else TradeReconciler(policy, existing)
    }

    /**
     * A [ConversionTradeReconciler] over the conversions another source already booked as transfers
     * that the batch's own trades could be duplicating, or null when the batch does not name a
     * conversion relationship type, has no trades an order does not claim, or nothing links up.
     *
     * Every step is a narrowing: the window is the policy's, the accounts are the ones the batch's
     * trades debit, and only a transfer that leaves one of those accounts *and* is the primary end of a
     * relationship of the named type is a conversion debit leg. The paired credit legs are loaded
     * only for the asset they received - their amount is not comparable (see
     * [ConversionTradeReconciler]).
     */
    private suspend fun buildConversionTradeReconciler(
        batch: ImportBatch,
        orderLinkedTradeKeys: Set<LocalTradeKey>,
    ): ConversionTradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val typeName = policy.conversionRelationshipTypeName ?: return null
        val relationships = relationshipRepository
        val creates =
            batch.trades
                .creates()
                .filter { it.timestamp != null && it.fromAccountId != null && it.key !in orderLinkedTradeKeys }
        if (creates.isEmpty()) return null
        val accountIds = creates.mapNotNullTo(mutableSetOf()) { it.fromAccountId }
        val minTs = creates.minOf { requireNotNull(it.timestamp) } - policy.window
        val maxTs = creates.maxOf { requireNotNull(it.timestamp) } + policy.window
        val typeId = relationshipTypeRepository.getByName(typeName).first()?.id ?: return null

        val debits =
            accountIds
                .flatMap { transactionRepository.getTransactionsByAccountAndDateRange(it, minTs, maxTs).first() }
                .distinctBy { it.id }
                // A conversion's debit leg is the one the asset LEFT the owner account on; the credit
                // leg runs the other way and is matched through the relationship, never directly.
                .filter { it.sourceAccountId in accountIds }
        val vacantDebits = observationRepository.getVacantTransactionIds(debits.map { it.id })
        val candidates = debits.filterNot { it.id in vacantDebits }
        if (candidates.isEmpty()) return null

        val creditIdByDebitId =
            relationships
                .getByTransactions(candidates.map { it.id })
                .filter { it.relationshipType.id == typeId }
                .mapNotNull { it.transferPair }
                .toMap()
        if (creditIdByDebitId.isEmpty()) return null
        val creditLegs = transactionRepository.getTransactionsByIds(creditIdByDebitId.values.toSet())
        val vacant = observationRepository.getVacantTransactionIds(creditLegs.values.map { it.id })

        val legs =
            candidates.mapNotNull { debit ->
                val creditId = creditIdByDebitId[debit.id] ?: return@mapNotNull null
                // A vacant credit leg (#1510) is no conversion to suppress a trade with: no record would take it back, and settling
                // would delete it, so the trade it records is booked instead.
                val credit = creditLegs[creditId]?.takeUnless { it.id in vacant } ?: return@mapNotNull null
                ExistingConversionLeg(
                    debitTransferId = debit.id,
                    accountId = debit.sourceAccountId,
                    amount = debit.amount,
                    timestamp = debit.timestamp,
                    creditAssetId = credit.amount.asset.id,
                )
            }
        return if (legs.isEmpty()) null else ConversionTradeReconciler(policy.window, legs, creates)
    }
}

/**
 * Multiset idempotency (see [NewTrade.occurrence]): N trades sharing the exact same field tuple (e.g. an exchange order split
 * into several byte-identical fills) must book as N trades, not collapse to one. [next] counts how many identical tuples this
 * pass has already claimed and returns that count as the occurrence index, so each one reuses a distinct existing row
 * (re-import) or inserts a distinct new one (first import). The key groups exactly the trades createTrades cannot tell apart, so
 * the timestamp is the persisted millisecond value — an Instant can carry sub-ms precision (e.g. Kraken's float epoch-seconds)
 * that the trade row does not. The description is deliberately absent: it plays no part in identifying a conversion (see
 * TradeSelect.sq's selectMatchingTradeId), so two rows describing one movement differently share an occurrence counter and the
 * second reuses the first's trade instead of double-booking.
 */
private class TradeOccurrences {
    private data class Key(
        val timestampMs: Long,
        val fromAccountId: AccountId,
        val fromAmount: Money,
        val toAccountId: AccountId,
        val toAmount: Money,
    )

    private val claimed = mutableMapOf<Key, Int>()

    /** The occurrence index of [trade], counting it. */
    fun next(trade: NewTrade): Int {
        val key = Key(trade.timestamp.toEpochMilliseconds(), trade.fromAccountId, trade.fromAmount, trade.toAccountId, trade.toAmount)
        val occurrence = claimed[key] ?: 0
        claimed[key] = occurrence + 1
        return occurrence
    }
}
