package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
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
    database: MoneyManagerDatabaseWrapper,
    reader: ObservationReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ObservationWriteRepository,
    ObservationReadRepository by reader {
    private val queries = database.observationWriteQueries
    private val selectQueries = database.observationSelectQueries

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

private fun TransactionId.kind(): TransactionKind = if (this is TransferId) TransactionKind.TRANSFER else TransactionKind.TRADE
