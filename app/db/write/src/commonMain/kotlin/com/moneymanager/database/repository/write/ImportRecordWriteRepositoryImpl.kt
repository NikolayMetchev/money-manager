package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.write.ImportRecordWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class ImportRecordWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: ImportRecordReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ImportRecordWriteRepository,
    ImportRecordReadRepository by reader {
    private val queries = database.importRecordWriteQueries
    private val selectQueries = database.importRecordSelectQueries

    override suspend fun insertRecord(
        key: ImportRecordKey,
        contentHash: String,
    ): ImportRecordId =
        withContext(coroutineContext) {
            queries.transactionWithResult {
                queries.insertRecord(contentHash)
                val id = queries.lastInsertedId().awaitAsOne()
                when (key) {
                    is ImportRecordKey.CsvRow -> queries.insertCsvRecord(id, key.csvImportId.id.toString(), key.rowIndex)
                    is ImportRecordKey.ApiItem -> queries.insertApiRecord(id, key.sessionId.id, key.requestId.id, key.jsonPath)
                }
                ImportRecordId(id)
            }
        }

    override suspend fun updateContentHash(
        id: ImportRecordId,
        contentHash: String,
    ) {
        withContext(coroutineContext) { queries.updateContentHash(contentHash, id.id) }
    }

    override suspend fun deleteRecord(id: ImportRecordId) {
        withContext(coroutineContext) { queries.deleteRecord(id.id) }
    }

    override suspend fun getOrCreateGroup(key: ImportGroupKey): ImportGroupId =
        withContext(coroutineContext) {
            queries.transactionWithResult {
                queries.insertGroupIfMissing(key.strategyId, key.groupName, key.keyValue)
                ImportGroupId(selectQueries.selectGroupIdByKey(key.strategyId, key.groupName, key.keyValue).awaitAsOne())
            }
        }

    override suspend fun setGroupMembers(
        groupId: ImportGroupId,
        members: Set<ImportRecordId>,
    ) {
        withContext(coroutineContext) {
            queries.transaction {
                queries.deleteGroupMembers(groupId.id)
                members.forEach { queries.insertGroupMember(groupId.id, it.id) }
            }
        }
    }

    override suspend fun deleteGroup(groupId: ImportGroupId) {
        withContext(coroutineContext) { queries.deleteGroup(groupId.id) }
    }
}
