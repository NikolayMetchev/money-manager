package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.write.CsvObservationCommit
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.RecordObservationInsert
import com.moneymanager.domain.repository.write.StaleObservationCommitException
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

    override suspend fun changeToken(): Long = withContext(coroutineContext) { database.dataChangeToken() }

    override suspend fun insertRecordObservations(
        contentHash: String,
        observations: List<RecordObservationInsert>,
    ): Map<ImportRecordKey, ImportRecordId> =
        withContext(coroutineContext) {
            queries.transactionWithResult {
                val records = mutableMapOf<ImportRecordKey, ImportRecordId>()
                for (observation in observations) {
                    val recordId = records.getOrPut(observation.record) { database.insertImportRecord(observation.record, contentHash) }
                    insert(ObservationOwner.Record(recordId), observation.slot, observation.transactionId, observation.content)
                }
                records
            }
        }

    override suspend fun applyObservationChanges(
        newTransactions: Collection<TransactionId>,
        observations: List<ObservationChange>,
        rows: List<MaterializedWrite>,
        csv: List<CsvObservationCommit>,
        reclaimed: Collection<ObservationId>,
    ): Map<TransactionId, TransactionId> {
        if (csv.isEmpty() && newTransactions.isEmpty() && observations.isEmpty() && rows.isEmpty() && reclaimed.isEmpty()) return emptyMap()
        return withContext(coroutineContext) {
            queries.transactionWithResult {
                val csvTables = csv.associateWith { verifyCsvCommit(it) }
                if (csv.isNotEmpty()) verifyCsvChanges(observations, rows)
                val ids = newTransactions.associateWith { allocate(it.kind()) }
                for (change in observations) {
                    val before = change.before
                    val after = change.after
                    if (before != null && after?.storedId != before.id) queries.deleteObservation(before.id.id)
                    if (after != null) {
                        val target = ids[after.transactionId] ?: after.transactionId
                        val storedId = after.storedId
                        if (storedId == null) {
                            insert(change.owner, change.slot, target, after.content)
                        } else {
                            checkNotNull(before)
                            if (before.content != after.content) update(storedId, after.content)
                            if (before.transactionId != target) assign(storedId, target)
                        }
                    }
                }
                reclaimed.forEach { queries.unreserve(it.id) }
                val allocated = ids.entries.associate { (local, stored) -> local.id to stored.id }
                for (write in rows) {
                    when (write) {
                        is MaterializedWrite.Insert -> {
                            insertRow(
                                write.copy(
                                    transactionId =
                                        allocated[write.transactionId] ?: write.transactionId,
                                ),
                            )
                        }

                        is MaterializedWrite.Update -> {
                            updateRow(write)
                        }

                        is MaterializedWrite.Delete -> {
                            deleteRow(write)
                        }
                    }
                }
                for ((commit, table) in csvTables) {
                    val tableManager = CsvTableManager(database)
                    commit.statuses.forEach { (index, status) ->
                        tableManager.updateRowStatus(table, index, status.name)
                    }
                    commit.hashes.forEach { (recordId, hash) ->
                        database.importRecordWriteQueries.updateContentHash(hash, recordId.id)
                    }
                    database.recordCsvApplication(
                        commit.importId,
                        commit.strategyId,
                        commit.strategyName,
                        commit.appliedAt,
                        commit.accountId,
                        commit.currencyId,
                    )
                }
                ids
            }
        }
    }

    private suspend fun verifyCsvCommit(csv: CsvObservationCommit): String {
        val file =
            database.csvImportSelectQueries.selectImportById(csv.importId.id.toString()).awaitAsOneOrNull()
                ?: throw StaleObservationCommitException()
        if (file.file_checksum != csv.fileChecksum || database.dataChangeToken() != csv.changeToken) {
            throw StaleObservationCommitException()
        }
        return file.table_name
    }

    private suspend fun verifyCsvChanges(
        changes: List<ObservationChange>,
        rows: List<MaterializedWrite>,
    ) {
        val valid =
            changes.all { change ->
                val before = change.before
                if (before == null) {
                    getObservationsOf(change.owner).none { it.slot == change.slot }
                } else {
                    getObservationsOfTransaction(before.transactionId).singleOrNull() == before
                }
            } &&
                rows.filterIsInstance<MaterializedWrite.Update>().all { update ->
                    getMaterialized(TransferId(update.transactionId)) == update.before
                }
        if (!valid) throw StaleObservationCommitException()
    }

    private suspend fun allocate(kind: TransactionKind): TransactionId {
        val transactionIds = database.transactionIdWriteQueries
        transactionIds.insert()
        val id = transactionIds.lastInsertedId().awaitAsOne()
        return when (kind) {
            TransactionKind.TRANSFER -> TransferId(id)
            TransactionKind.TRADE -> TradeId(id)
        }
    }

    override suspend fun insertObservation(
        owner: ObservationOwner,
        slot: String,
        transactionId: TransactionId,
        content: ObservationContent,
    ): ObservationId = withContext(coroutineContext) { queries.transactionWithResult { insert(owner, slot, transactionId, content) } }

    private suspend fun insert(
        owner: ObservationOwner,
        slot: String,
        transactionId: TransactionId,
        content: ObservationContent,
    ): ObservationId {
        require(transactionId.kind() == content.values.kind) {
            "a ${content.values.kind} observation can't be on transaction $transactionId"
        }
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
        return ObservationId(id)
    }

    override suspend fun updateContent(
        id: ObservationId,
        content: ObservationContent,
    ): Unit = withContext(coroutineContext) { queries.transaction { update(id, content) } }

    private suspend fun update(
        id: ObservationId,
        content: ObservationContent,
    ) {
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
        queries.deleteOwnEnd(id.id)
        writeDetails(id.id, content)
    }

    private suspend fun writeDetails(
        id: Long,
        content: ObservationContent,
    ) {
        content.attributes.forEach { queries.insertAttribute(id, it.typeId.id, it.groupKey, it.value) }
        if (content.facts.approximateTime) queries.insertApproximateTime(id)
        content.facts.placeholderEnds.forEach { queries.insertPlaceholderEnd(id, it.dbName) }
        if (content.facts.grossAmount) queries.insertGrossAmount(id)
        content.facts.ownEnd?.let { queries.insertOwnEnd(id, it.dbName) }
    }

    override suspend fun assignTransaction(
        id: ObservationId,
        transactionId: TransactionId,
    ): Unit = withContext(coroutineContext) { queries.transaction { assign(id, transactionId) } }

    private suspend fun assign(
        id: ObservationId,
        transactionId: TransactionId,
    ) {
        val kind = TransactionKind.valueOf(selectQueries.selectKind(id.id).awaitAsOne())
        require(kind == transactionId.kind()) { "observation $id is a $kind, not on transaction $transactionId" }
        queries.assignTransaction(transactionId.id, id.id)
    }

    override suspend fun deleteObservation(id: ObservationId) {
        withContext(coroutineContext) { queries.deleteObservation(id.id) }
    }

    override suspend fun deleteObservationsOfTransactions(transactionIds: Collection<TransactionId>) {
        if (transactionIds.isEmpty()) return
        withContext(coroutineContext) {
            queries.transaction { transactionIds.forEach { queries.deleteObservationsOfTransaction(it.id) } }
        }
    }

    override suspend fun reserveApiSession(sessionId: ApiSessionId) {
        withContext(coroutineContext) { queries.reserveApiSession(sessionId.id) }
    }

    override suspend fun unreserve(id: ObservationId) {
        withContext(coroutineContext) { queries.unreserve(id.id) }
    }

    override suspend fun releaseTransactions(transactionIds: Collection<TransactionId>) {
        if (transactionIds.isEmpty()) return
        withContext(coroutineContext) {
            queries.transaction {
                transactionIds.forEach {
                    database.relationshipWriteQueries.deleteMembersOfTransaction(it.id)
                    queries.deleteObservationsOfTransaction(it.id)
                }
            }
        }
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
