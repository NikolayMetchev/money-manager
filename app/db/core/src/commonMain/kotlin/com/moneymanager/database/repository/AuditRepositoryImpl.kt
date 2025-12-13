@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuditRepositoryImpl(
    database: MoneyManagerDatabase,
) : AuditRepository {
    private val queries = database.auditQueries

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            queries.selectAuditHistoryForTransfer(transferId.toString())
                .executeAsList()
                .let(TransferAuditEntryMapper::mapList)
        }
}
