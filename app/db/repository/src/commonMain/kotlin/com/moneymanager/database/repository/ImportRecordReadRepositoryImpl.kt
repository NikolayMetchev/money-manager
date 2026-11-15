package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.observation.ImportGroup
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.repository.ImportRecordReadRepository
import kotlin.uuid.Uuid

class ImportRecordReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : ImportRecordReadRepository {
    private val queries = database.importRecordSelectQueries

    override suspend fun getRecord(id: ImportRecordId): ImportRecord? =
        queries
            .selectRecordById(id.id) { recordId, contentHash, csvImportId, rowIndex, sessionId, requestId, jsonPath ->
                val key =
                    if (csvImportId != null && rowIndex != null) {
                        ImportRecordKey.CsvRow(CsvImportId(Uuid.parse(csvImportId)), rowIndex)
                    } else {
                        ImportRecordKey.ApiItem(
                            ApiSessionId(checkNotNull(sessionId) { "import record $recordId has no kind" }),
                            ApiRequestId(checkNotNull(requestId)),
                            checkNotNull(jsonPath),
                        )
                    }
                ImportRecord(ImportRecordId(recordId), key, contentHash)
            }.awaitAsOneOrNull()

    override suspend fun getCsvImportRecords(csvImportId: CsvImportId): List<ImportRecord> =
        queries
            .selectCsvRecordsByImport(csvImportId.id.toString()) { recordId, contentHash, rowIndex ->
                ImportRecord(ImportRecordId(recordId), ImportRecordKey.CsvRow(csvImportId, rowIndex), contentHash)
            }.awaitAsList()

    override suspend fun getApiSessionRecords(sessionId: ApiSessionId): List<ImportRecord> =
        queries
            .selectApiRecordsBySession(sessionId.id) { recordId, contentHash, requestId, jsonPath ->
                ImportRecord(
                    ImportRecordId(recordId),
                    ImportRecordKey.ApiItem(sessionId, ApiRequestId(requestId), jsonPath),
                    contentHash,
                )
            }.awaitAsList()

    override suspend fun getGroup(key: ImportGroupKey): ImportGroup? =
        queries
            .selectGroupIdByKey(key.strategyId, key.groupName, key.keyValue)
            .awaitAsOneOrNull()
            ?.let { ImportGroup(ImportGroupId(it), key, members(it)) }

    override suspend fun getGroupsOfStrategy(strategyId: String): List<ImportGroup> =
        queries
            .selectGroupsByStrategy(strategyId)
            .awaitAsList()
            .map { ImportGroup(ImportGroupId(it.id), ImportGroupKey(strategyId, it.group_name, it.key_value), members(it.id)) }

    private suspend fun members(groupId: Long): Set<ImportRecordId> =
        queries.selectGroupMembers(groupId).awaitAsList().mapTo(LinkedHashSet(), ::ImportRecordId)
}
