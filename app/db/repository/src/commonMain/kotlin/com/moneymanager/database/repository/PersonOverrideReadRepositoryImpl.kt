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

    override suspend fun getAllPins(): List<ObservationPin> =
        queries
            .selectAllPins { observationId, transactionId, kind ->
                ObservationPin(ObservationId(observationId), transactionIdOf(TransactionKind.valueOf(kind), transactionId))
            }.awaitAsList()

    override suspend fun getAllSplits(): Set<ObservationSplit> =
        queries
            .selectAllSplits { a, b -> ObservationSplit(ObservationId(a), ObservationId(b)) }
            .awaitAsList()
            .toSet()

    override suspend fun getSplits(observationIds: Collection<ObservationId>): Set<ObservationSplit> =
        queries
            .selectSplits(observationIds.map { it.id }) { a, b -> ObservationSplit(ObservationId(a), ObservationId(b)) }
            .awaitAsList()
            .toSet()

    private companion object {
        val BY_TYPE_AND_GROUP = compareBy<AttributeOverride>({ it.typeId.id }, { it.groupKey })
    }
}
