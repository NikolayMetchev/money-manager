package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import kotlinx.coroutines.flow.first

/** Everything about one row of an account's transactions: its legs, attributes, linked transfers, provenance and observations. */
class TransactionDetailQuery(
    private val transactionRepository: TransactionReadRepository,
    private val tradeRepository: TradeReadRepository,
    private val relationshipRepository: RelationshipReadRepository,
    private val sourceRepository: TransferSourceReadRepository,
    private val observationReads: ObservationReads,
) {
    /** The detail of [row], or null when it no longer exists. */
    suspend fun detail(row: AccountRow): TransactionDetail? =
        when (row.kind) {
            TransactionKind.TRANSFER -> transferDetail(row, TransferId(row.transactionId.id))
            TransactionKind.TRADE -> tradeDetail(row, TradeId(row.transactionId.id))
        }

    /** What [transactionId] is made of, for a screen that shows it without the rest of the detail. */
    suspend fun observations(transactionId: TransactionId): TransactionObservations =
        observationReads.observationsOf(
            transactionId,
            emptyList(),
            (transactionId as? TransferId)?.let { sourceRepository.getSourcesForTransaction(it) }.orEmpty(),
        )

    private suspend fun transferDetail(
        row: AccountRow,
        id: TransferId,
    ): TransactionDetail? {
        val transfer = transactionRepository.getTransactionById(id.id).first() ?: return null
        val sources = sourceRepository.getSourcesForTransaction(id)
        val relationships = relationshipRepository.getByTransaction(id).first()
        val others =
            transactionRepository.getTransactionsByIds(relationships.flatMap { it.othersThan(id) }.filterIsInstance<TransferId>())
        val links =
            relationships.flatMap { relationship ->
                // This transfer owns the link when it is the pair's primary end; an undirected member owns none.
                val outgoing = relationship.primary?.id == id.id
                relationship
                    .othersThan(id)
                    .filterIsInstance<TransferId>()
                    .mapNotNull { other -> others[other]?.let { LinkedTransfer(relationship.relationshipType.name, outgoing, it) } }
            }
        return TransactionDetail(
            row = row,
            legs = listOf(TransactionLeg(transfer.sourceAccountId, transfer.targetAccountId, transfer.amount)),
            attributes = transfer.attributes.map { DetailAttribute(it.attributeType.name, it.value) },
            links = links,
            sources = sources,
            observations = observationReads.observationsOf(id, listOf(transfer.amount.asset), sources),
        )
    }

    private suspend fun tradeDetail(
        row: AccountRow,
        id: TradeId,
    ): TransactionDetail? {
        val trade = tradeRepository.getTradeById(id).first() ?: return null
        return TransactionDetail(
            row = row,
            legs =
                listOf(
                    TransactionLeg(trade.fromAccountId, trade.toAccountId, trade.from),
                    TransactionLeg(trade.toAccountId, trade.fromAccountId, trade.to),
                ),
            attributes = tradeRepository.getAttributes(id).first().map { DetailAttribute(it.attributeType.name, it.value) },
            links = emptyList(),
            sources = emptyList(),
            observations = observationReads.observationsOf(id, listOf(trade.from.asset, trade.to.asset)),
        )
    }
}

data class TransactionDetail(
    val row: AccountRow,
    /** What moved: a transfer's one movement, or a trade's two sides. */
    val legs: List<TransactionLeg>,
    val attributes: List<DetailAttribute>,
    /** Transfers linked to this one (fee, pass-through, reconciled, …), by relationship type name. */
    val links: List<LinkedTransfer>,
    /** Where each revision came from, newest first. Empty for trades. */
    val sources: List<SourceRecord>,
    /** Who observed it, and what a person pinned, split and overrode on it. */
    val observations: TransactionObservations = TransactionObservations(),
)

data class TransactionLeg(
    val from: AccountId,
    val to: AccountId,
    val amount: Money,
)

data class DetailAttribute(
    val name: String,
    val value: String,
)

data class LinkedTransfer(
    val relationship: String,
    /** True when this transfer is the relationship's first side (a fee's main transfer, a pass-through's funding leg). */
    val outgoing: Boolean,
    val transfer: Transfer,
)
