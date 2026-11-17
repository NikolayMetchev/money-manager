package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.PersonOverrideReadRepository

class PersonOverrideReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonOverrideReadRepository {
    private val queries = database.personOverrideSelectQueries

    override suspend fun getTransferOverrides(transferId: TransferId): Map<TransferField, String> =
        queries
            .selectTransferOverrides(transferId.id) { field, value -> TransferField.fromColumn(field) to value }
            .awaitAsList()
            .toMap()

    override suspend fun getTransferAttributeOverrides(transferId: TransferId): List<AttributeOverride> {
        val set =
            queries
                .selectTransferAttributeOverrides(transferId.id) { typeId, groupKey, value ->
                    AttributeOverride.Set(AttributeTypeId(typeId), groupKey, value)
                }.awaitAsList()
        val removed =
            queries
                .selectTransferAttributeRemovals(transferId.id) { typeId, groupKey ->
                    AttributeOverride.Removed(AttributeTypeId(typeId), groupKey)
                }.awaitAsList()
        return (set + removed).sortedWith(BY_TYPE_AND_GROUP)
    }

    override suspend fun getTradeAttributeOverrides(tradeId: TradeId): List<AttributeOverride> {
        val set =
            queries
                .selectTradeAttributeOverrides(tradeId.id) { typeId, value -> AttributeOverride.Set(AttributeTypeId(typeId), "", value) }
                .awaitAsList()
        val removed =
            queries
                .selectTradeAttributeRemovals(tradeId.id)
                .awaitAsList()
                .map { AttributeOverride.Removed(AttributeTypeId(it), "") }
        return (set + removed).sortedWith(BY_TYPE_AND_GROUP)
    }

    override suspend fun getOverriddenTransfers(transferIds: Collection<TransferId>): Set<TransferId> =
        queryInChunks(transferIds.map { it.id }, MAX_IDS_PER_QUERY / OVERRIDE_TABLES) { chunk ->
            queries.selectOverriddenTransferIds(chunk).awaitAsList()
        }.mapTo(mutableSetOf()) { TransferId(it) }

    override suspend fun getTradeAttributeOverrides(tradeIds: Collection<TradeId>): Map<TradeId, List<AttributeOverride>> {
        val ids = tradeIds.map { it.id }
        val set =
            queryInChunks(ids, MAX_IDS_PER_QUERY) { chunk ->
                queries
                    .selectTradeAttributeOverridesOf(chunk) { tradeId, typeId, value ->
                        TradeId(tradeId) to AttributeOverride.Set(AttributeTypeId(typeId), "", value)
                    }.awaitAsList()
            }
        val removed =
            queryInChunks(ids, MAX_IDS_PER_QUERY) { chunk ->
                queries
                    .selectTradeAttributeRemovalsOf(chunk) { tradeId, typeId ->
                        TradeId(tradeId) to AttributeOverride.Removed(AttributeTypeId(typeId), "")
                    }.awaitAsList()
            }
        return (set + removed).groupBy({ it.first }, { it.second }).mapValues { (_, overrides) -> overrides.sortedWith(BY_TYPE_AND_GROUP) }
    }

    override suspend fun getPins(observationIds: Collection<ObservationId>): List<ObservationPin> =
        queries
            .selectPins(observationIds.map { it.id }) { observationId, transactionId, kind ->
                ObservationPin(ObservationId(observationId), transactionIdOf(TransactionKind.valueOf(kind), transactionId))
            }.awaitAsList()

    override suspend fun getPinsOnTransactions(transactionIds: Collection<TransactionId>): List<ObservationPin> =
        if (transactionIds.isEmpty()) {
            emptyList()
        } else {
            queries
                .selectPinsOnTransactions(transactionIds.map { it.id }) { observationId, transactionId, kind ->
                    ObservationPin(ObservationId(observationId), transactionIdOf(TransactionKind.valueOf(kind), transactionId))
                }.awaitAsList()
        }

    override suspend fun getSplits(observationIds: Collection<ObservationId>): Set<ObservationSplit> =
        queries
            .selectSplits(observationIds.map { it.id }) { a, b -> ObservationSplit(ObservationId(a), ObservationId(b)) }
            .awaitAsList()
            .toSet()

    private companion object {
        val BY_TYPE_AND_GROUP = compareBy<AttributeOverride>({ it.typeId.id }, { it.groupKey })

        // selectOverriddenTransferIds binds its ids once per override table.
        const val OVERRIDE_TABLES = 3
    }
}
