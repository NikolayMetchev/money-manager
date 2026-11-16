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

    override suspend fun getRecord(id: ImportRecordId): ImportRecord? = queries.selectRecordById(id.id, ::toRecord).awaitAsOneOrNull()

    override suspend fun getRecords(ids: Collection<ImportRecordId>): List<ImportRecord> =
        ids.distinct().chunked(ID_CHUNK).flatMap { chunk -> queries.selectRecordsByIds(chunk.map { it.id }, ::toRecord).awaitAsList() }

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

    override suspend fun getGroupById(id: ImportGroupId): ImportGroup? =
        queries
            .selectGroupById(id.id)
            .awaitAsOneOrNull()
            ?.let { ImportGroup(id, ImportGroupKey(it.strategy_id, it.group_name, it.key_value), members(id.id)) }

    override suspend fun getGroupsOfStrategy(strategyId: String): List<ImportGroup> =
        queries
            .selectGroupsByStrategy(strategyId)
            .awaitAsList()
            .map { ImportGroup(ImportGroupId(it.id), ImportGroupKey(strategyId, it.group_name, it.key_value), members(it.id)) }

    override suspend fun getGroupsById(ids: Collection<ImportGroupId>): List<ImportGroup> =
        ids.distinct().chunked(ID_CHUNK).flatMap { chunk ->
            val idValues = chunk.map { it.id }
            val members =
                queries
                    .selectMembersOfGroups(idValues)
                    .awaitAsList()
                    .groupBy({ it.group_id }, { ImportRecordId(it.record_id) })
            queries.selectGroupsByIds(idValues).awaitAsList().map { group ->
                ImportGroup(
                    ImportGroupId(group.id),
                    ImportGroupKey(group.strategy_id, group.group_name, group.key_value),
                    members[group.id].orEmpty().toCollection(LinkedHashSet()),
                )
            }
        }

    private suspend fun members(groupId: Long): Set<ImportRecordId> =
        queries.selectGroupMembers(groupId).awaitAsList().mapTo(LinkedHashSet(), ::ImportRecordId)
}

/** Ids per `IN` list: well under SQLite's bound-variable limit on every platform. */
private const val ID_CHUNK = 500

@Suppress("LongParameterList")
private fun toRecord(
    recordId: Long,
    contentHash: String,
    csvImportId: String?,
    rowIndex: Long?,
    sessionId: Long?,
    requestId: Long?,
    jsonPath: String?,
): ImportRecord {
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
    return ImportRecord(ImportRecordId(recordId), key, contentHash)
}
