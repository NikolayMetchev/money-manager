package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.observation.ImportGroup
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId

/** Read side of import records and the groups that join them (#1419). */
interface ImportRecordReadRepository {
    suspend fun getRecord(id: ImportRecordId): ImportRecord?

    /** Every record of a staged file, by row. */
    suspend fun getCsvImportRecords(csvImportId: CsvImportId): List<ImportRecord>

    /** Every record of an API session, by request and path. */
    suspend fun getApiSessionRecords(sessionId: ApiSessionId): List<ImportRecord>

    suspend fun getGroup(key: ImportGroupKey): ImportGroup?

    suspend fun getGroupById(id: ImportGroupId): ImportGroup?

    /** Every group of a strategy, by name and key. */
    suspend fun getGroupsOfStrategy(strategyId: String): List<ImportGroup>
}
