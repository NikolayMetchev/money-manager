package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class PersonOverrideWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: PersonOverrideReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : PersonOverrideWriteRepository,
    PersonOverrideReadRepository by reader {
    private val queries = database.personOverrideWriteQueries

    override suspend fun setTransferOverride(
        transferId: TransferId,
        field: TransferField,
        value: String,
    ) {
        withContext(coroutineContext) { queries.upsertTransferOverride(transferId.id, field.column, value) }
    }

    override suspend fun clearTransferOverride(
        transferId: TransferId,
        field: TransferField,
    ) {
        withContext(coroutineContext) { queries.deleteTransferOverride(transferId.id, field.column) }
    }

    override suspend fun setTransferAttributeOverride(
        transferId: TransferId,
        override: AttributeOverride,
    ) {
        withContext(coroutineContext) {
            val id = transferId.id
            val typeId = override.typeId.id
            queries.transaction {
                queries.deleteTransferAttributeOverride(id, typeId, override.groupKey)
                queries.deleteTransferAttributeRemoval(id, typeId, override.groupKey)
                when (override) {
                    is AttributeOverride.Set -> queries.upsertTransferAttributeOverride(id, typeId, override.groupKey, override.value)
                    is AttributeOverride.Removed -> queries.insertTransferAttributeRemoval(id, typeId, override.groupKey)
                }
            }
        }
    }

    override suspend fun clearTransferAttributeOverride(
        transferId: TransferId,
        typeId: AttributeTypeId,
        groupKey: String,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                queries.deleteTransferAttributeOverride(transferId.id, typeId.id, groupKey)
                queries.deleteTransferAttributeRemoval(transferId.id, typeId.id, groupKey)
            }
        }
    }

    override suspend fun setTradeAttributeOverride(
        tradeId: TradeId,
        override: AttributeOverride,
    ) {
        require(override.groupKey.isEmpty()) { "trade attributes have no group key" }
        withContext(coroutineContext) {
            val id = tradeId.id
            val typeId = override.typeId.id
            queries.transaction {
                queries.deleteTradeAttributeOverride(id, typeId)
                queries.deleteTradeAttributeRemoval(id, typeId)
                when (override) {
                    is AttributeOverride.Set -> queries.upsertTradeAttributeOverride(id, typeId, override.value)
                    is AttributeOverride.Removed -> queries.insertTradeAttributeRemoval(id, typeId)
                }
            }
        }
    }

    override suspend fun clearTradeAttributeOverride(
        tradeId: TradeId,
        typeId: AttributeTypeId,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                queries.deleteTradeAttributeOverride(tradeId.id, typeId.id)
                queries.deleteTradeAttributeRemoval(tradeId.id, typeId.id)
            }
        }
    }

    override suspend fun pin(pin: ObservationPin) {
        withContext(coroutineContext) { queries.upsertPin(pin.observationId.id, pin.transactionId.id) }
    }

    override suspend fun unpin(observationId: ObservationId) {
        withContext(coroutineContext) { queries.deletePin(observationId.id) }
    }

    override suspend fun split(split: ObservationSplit) {
        withContext(coroutineContext) { queries.insertSplit(split.first.id, split.second.id) }
    }

    override suspend fun unsplit(split: ObservationSplit) {
        withContext(coroutineContext) { queries.deleteSplit(split.first.id, split.second.id) }
    }
}
