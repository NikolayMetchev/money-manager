package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class ObservationWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: ObservationReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ObservationWriteRepository,
    ObservationReadRepository by reader {
    private val queries = database.observationWriteQueries
    private val selectQueries = database.observationSelectQueries

    override suspend fun allocateTransactionId(kind: TransactionKind): TransactionId =
        withContext(coroutineContext) {
            val transactionIds = database.transactionIdWriteQueries
            val id =
                transactionIds.transactionWithResult {
                    transactionIds.insert()
                    transactionIds.lastInsertedId().awaitAsOne()
                }
            when (kind) {
                TransactionKind.TRANSFER -> TransferId(id)
                TransactionKind.TRADE -> TradeId(id)
            }
        }

    override suspend fun insertObservation(
        owner: ObservationOwner,
        slot: String,
        transactionId: TransactionId,
        content: ObservationContent,
    ): ObservationId =
        withContext(coroutineContext) {
            require(transactionId.kind() == content.values.kind) {
                "a ${content.values.kind} observation can't be on transaction $transactionId"
            }
            queries.transactionWithResult {
                queries.insertObservation(transactionId.id, content.values.kind.name)
                val id = queries.lastInsertedId().awaitAsOne()
                when (owner) {
                    is ObservationOwner.Record -> queries.insertRecordOwner(id, owner.recordId.id, slot)
                    is ObservationOwner.Group -> queries.insertGroupOwner(id, owner.groupId.id, slot)
                    is ObservationOwner.Manual -> queries.insertManualOwner(id, owner.entitySourceId, slot)
                }
                when (val values = content.values) {
                    is ObservedValues.Transfer -> {
                        queries.insertTransferValues(
                            id,
                            values.timestamp.toEpochMilliseconds(),
                            values.description,
                            values.sourceAccountId.id,
                            values.targetAccountId.id,
                            values.assetId.id,
                            values.amount.toString(),
                        )
                    }

                    is ObservedValues.Trade -> {
                        queries.insertTradeValues(
                            id,
                            values.timestamp.toEpochMilliseconds(),
                            values.description,
                            values.fromAccountId.id,
                            values.fromAssetId.id,
                            values.fromAmount.toString(),
                            values.toAccountId.id,
                            values.toAssetId.id,
                            values.toAmount.toString(),
                        )
                    }
                }
                writeDetails(id, content)
                ObservationId(id)
            }
        }

    override suspend fun updateContent(
        id: ObservationId,
        content: ObservationContent,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                val kind = TransactionKind.valueOf(selectQueries.selectKind(id.id).awaitAsOne())
                require(kind == content.values.kind) { "observation $id is a $kind, not a ${content.values.kind}" }
                when (val values = content.values) {
                    is ObservedValues.Transfer -> {
                        queries.updateTransferValues(
                            values.timestamp.toEpochMilliseconds(),
                            values.description,
                            values.sourceAccountId.id,
                            values.targetAccountId.id,
                            values.assetId.id,
                            values.amount.toString(),
                            id.id,
                        )
                    }

                    is ObservedValues.Trade -> {
                        queries.updateTradeValues(
                            values.timestamp.toEpochMilliseconds(),
                            values.description,
                            values.fromAccountId.id,
                            values.fromAssetId.id,
                            values.fromAmount.toString(),
                            values.toAccountId.id,
                            values.toAssetId.id,
                            values.toAmount.toString(),
                            id.id,
                        )
                    }
                }
                queries.deleteAttributes(id.id)
                queries.deleteApproximateTime(id.id)
                queries.deletePlaceholderEnds(id.id)
                queries.deleteGrossAmount(id.id)
                writeDetails(id.id, content)
            }
        }
    }

    private suspend fun writeDetails(
        id: Long,
        content: ObservationContent,
    ) {
        content.attributes.forEach { queries.insertAttribute(id, it.typeId.id, it.groupKey, it.value) }
        if (content.facts.approximateTime) queries.insertApproximateTime(id)
        content.facts.placeholderEnds.forEach { queries.insertPlaceholderEnd(id, it.dbName) }
        if (content.facts.grossAmount) queries.insertGrossAmount(id)
    }

    override suspend fun assignTransaction(
        id: ObservationId,
        transactionId: TransactionId,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                val kind = TransactionKind.valueOf(selectQueries.selectKind(id.id).awaitAsOne())
                require(kind == transactionId.kind()) { "observation $id is a $kind, not on transaction $transactionId" }
                queries.assignTransaction(transactionId.id, id.id)
            }
        }
    }

    override suspend fun deleteObservation(id: ObservationId) {
        withContext(coroutineContext) { queries.deleteObservation(id.id) }
    }

    override suspend fun moveAccountReferences(
        mergeId: MergeId,
        deletedAccountId: AccountId,
        survivingAccountId: AccountId,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                queries.recordTransferMergeMoves(mergeId.id, deletedAccountId.id)
                queries.recordTradeMergeMoves(mergeId.id, deletedAccountId.id)
                moveRecorded(mergeId, survivingAccountId)
            }
        }
    }

    override suspend fun restoreAccountReferences(
        mergeId: MergeId,
        restoredAccountId: AccountId,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                moveRecorded(mergeId, restoredAccountId)
                queries.deleteMergeMoves(mergeId.id)
            }
        }
    }

    override suspend fun applyMaterialization(writes: List<MaterializedWrite>) {
        if (writes.isEmpty()) return
        withContext(coroutineContext) {
            queries.transaction {
                writes.forEach { write ->
                    when (write) {
                        is MaterializedWrite.Insert -> insertRow(write)
                        is MaterializedWrite.Update -> updateRow(write)
                        is MaterializedWrite.Delete -> deleteRow(write)
                    }
                }
            }
        }
    }

    private suspend fun insertRow(write: MaterializedWrite.Insert) {
        val id = write.transactionId
        when (val values = write.row.values) {
            is ObservedValues.Transfer -> {
                database.transferWriteQueries.insert(
                    id = id,
                    revision_id = FIRST_REVISION,
                    timestamp = values.timestamp.toEpochMilliseconds(),
                    description = values.description,
                    source_account_id = values.sourceAccountId.id,
                    target_account_id = values.targetAccountId.id,
                    asset_id = values.assetId.id,
                    amount = values.amount.toString(),
                )
                inCreationMode {
                    write.row.attributes.forEach { insertTransferAttribute(id, it) }
                }
                database.recordSource(deviceId, EntityType.TRANSFER, id, FIRST_REVISION, write.source)
            }

            is ObservedValues.Trade -> {
                database.tradeWriteQueries.insert(
                    id,
                    FIRST_REVISION,
                    values.timestamp.toEpochMilliseconds(),
                    values.description,
                    values.fromAccountId.id,
                    values.fromAssetId.id,
                    values.fromAmount.toString(),
                    values.toAccountId.id,
                    values.toAssetId.id,
                    values.toAmount.toString(),
                )
                write.row.attributes.forEach { database.tradeWriteQueries.upsertAttribute(id, it.typeId.id, it.value) }
                database.recordSource(deviceId, EntityType.TRADE, id, FIRST_REVISION, write.source)
            }
        }
    }

    private suspend fun updateRow(write: MaterializedWrite.Update) {
        val id = write.transactionId
        val before = write.before.attributes.associateBy { it.typeId to it.groupKey }
        val after = write.after.attributes.associateBy { it.typeId to it.groupKey }
        val removed = before.keys - after.keys
        val changed = after.values.filter { before[it.typeId to it.groupKey] != it }
        when (val values = write.after.values) {
            is ObservedValues.Transfer -> {
                // One revision for the whole write: the row update bumps it (or a bare bump when only attributes
                // change), and the attributes follow in creation mode so they don't bump it again.
                if (values != write.before.values) {
                    database.transferWriteQueries.update(
                        timestamp = values.timestamp.toEpochMilliseconds(),
                        description = values.description,
                        source_account_id = values.sourceAccountId.id,
                        target_account_id = values.targetAccountId.id,
                        asset_id = values.assetId.id,
                        amount = values.amount.toString(),
                        id = id,
                    )
                } else {
                    database.transferWriteQueries.bumpRevisionOnly(id)
                }
                inCreationMode {
                    removed.forEach { (typeId, groupKey) ->
                        database.transferAttributeWriteQueries.deleteBySlot(id, typeId.id, groupKey)
                    }
                    changed.forEach { attribute ->
                        if (attribute.typeId to attribute.groupKey in before) {
                            database.transferAttributeWriteQueries.updateValueBySlot(
                                attribute.value,
                                id,
                                attribute.typeId.id,
                                attribute.groupKey,
                            )
                        } else {
                            insertTransferAttribute(id, attribute)
                        }
                    }
                }
                val revision = database.transferSelectQueries.selectRevisionById(id).awaitAsOne()
                database.recordSource(deviceId, EntityType.TRANSFER, id, revision, write.source)
            }

            is ObservedValues.Trade -> {
                if (values != write.before.values) {
                    database.tradeWriteQueries.update(
                        values.timestamp.toEpochMilliseconds(),
                        values.description,
                        values.fromAccountId.id,
                        values.fromAssetId.id,
                        values.fromAmount.toString(),
                        values.toAccountId.id,
                        values.toAssetId.id,
                        values.toAmount.toString(),
                        id,
                    )
                }
                database.tradeWriteQueries.bumpRevision(id)
                removed.forEach { (typeId, _) -> database.tradeWriteQueries.deleteAttribute(id, typeId.id) }
                changed.forEach { database.tradeWriteQueries.upsertAttribute(id, it.typeId.id, it.value) }
                val revision = database.tradeSelectQueries.selectRevision(id).awaitAsOne()
                database.recordSource(deviceId, EntityType.TRADE, id, revision, write.source)
            }
        }
    }

    private suspend fun deleteRow(write: MaterializedWrite.Delete) {
        when (write.kind) {
            TransactionKind.TRANSFER -> database.transferWriteQueries.delete(write.transactionId)
            TransactionKind.TRADE -> database.tradeWriteQueries.delete(write.transactionId)
        }
    }

    private suspend fun insertTransferAttribute(
        transactionId: Long,
        attribute: ObservationAttribute,
    ) {
        database.transferAttributeWriteQueries.insert(
            transaction_id = transactionId,
            attribute_type_id = attribute.typeId.id,
            attribute_value = attribute.value,
            group_key = attribute.groupKey,
        )
    }

    private suspend fun inCreationMode(block: suspend () -> Unit) {
        database.beginCreationMode()
        try {
            block()
        } finally {
            database.endCreationMode()
        }
    }

    private suspend fun moveRecorded(
        mergeId: MergeId,
        accountId: AccountId,
    ) {
        queries.moveMergedSources(accountId.id, mergeId.id)
        queries.moveMergedTargets(accountId.id, mergeId.id)
        queries.moveMergedFroms(accountId.id, mergeId.id)
        queries.moveMergedTos(accountId.id, mergeId.id)
    }
}

private const val FIRST_REVISION = 1L

private fun TransactionId.kind(): TransactionKind = if (this is TransferId) TransactionKind.TRANSFER else TransactionKind.TRADE
