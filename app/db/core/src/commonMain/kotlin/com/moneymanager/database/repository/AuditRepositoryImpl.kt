@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.mapper.TransferAuditEntryWithSourceMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class AuditRepositoryImpl(
    database: MoneyManagerDatabase,
) : AuditRepository {
    private val queries = database.auditQueries
    private val attributeAuditQueries = database.transferAttributeAuditQueries

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries.selectAuditHistoryForTransfer(transferId.toString())
                    .executeAsList()
                    .map(TransferAuditEntryMapper::map)

            attachAttributeChanges(transferId, entries)
        }

    override suspend fun getAuditHistoryForTransferWithSource(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries.selectAuditHistoryForTransferWithSource(transferId.toString())
                    .executeAsList()
                    .map(TransferAuditEntryWithSourceMapper::map)

            attachAttributeChanges(transferId, entries)
        }

    private fun attachAttributeChanges(
        transferId: TransferId,
        entries: List<TransferAuditEntry>,
    ): List<TransferAuditEntry> {
        // Fetch all attribute audit entries for this transfer
        val allAttributeChanges =
            attributeAuditQueries.selectAllByTransaction(transferId.id.toString())
                .executeAsList()
                .map { row ->
                    TransferAttributeAuditEntry(
                        id = row.id,
                        transactionId = TransferId(Uuid.parse(row.transactionId)),
                        revisionId = row.revisionId,
                        attributeType =
                            AttributeType(
                                id = AttributeTypeId(row.attributeType_id),
                                name = row.attributeType_name,
                            ),
                        auditType = mapAuditType(row.auditType_name),
                        value = row.attributeValue,
                    )
                }

        // Group attribute changes by revisionId
        val changesByRevision = allAttributeChanges.groupBy { it.revisionId }

        // Attach attribute changes to each audit entry based on revisionId
        return entries.map { entry ->
            entry.copy(attributeChanges = changesByRevision[entry.revisionId].orEmpty())
        }
    }

    private fun mapAuditType(name: String): AuditType =
        when (name) {
            "INSERT" -> AuditType.INSERT
            "UPDATE" -> AuditType.UPDATE
            "DELETE" -> AuditType.DELETE
            else -> error("Unknown audit type: $name")
        }
}
