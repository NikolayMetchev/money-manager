@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.mapper.TransferAuditEntryWithSourceMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.TransferAttribute
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
    private val attributeQueries = database.transferAttributeQueries

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries.selectAuditHistoryForTransfer(transferId.toString())
                    .executeAsList()
                    .map(TransferAuditEntryMapper::map)

            attachAttributes(transferId, entries)
        }

    override suspend fun getAuditHistoryForTransferWithSource(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries.selectAuditHistoryForTransferWithSource(transferId.toString())
                    .executeAsList()
                    .map(TransferAuditEntryWithSourceMapper::map)

            attachAttributes(transferId, entries)
        }

    private fun attachAttributes(
        transferId: TransferId,
        entries: List<TransferAuditEntry>,
    ): List<TransferAuditEntry> {
        // Fetch all attributes for this transfer
        val allAttributes =
            attributeQueries.selectAllByTransaction(transferId.id.toString())
                .executeAsList()
                .map { row ->
                    TransferAttribute(
                        id = row.id,
                        transactionId = TransferId(Uuid.parse(row.transactionId)),
                        revisionId = row.revisionId,
                        attributeType =
                            AttributeType(
                                id = AttributeTypeId(row.attributeType_id),
                                name = row.attributeType_name,
                            ),
                        value = row.attributeValue,
                    )
                }

        // Group attributes by revisionId
        val attributesByRevision = allAttributes.groupBy { it.revisionId }

        // Attach attributes to each audit entry based on revisionId
        // For UPDATE entries: the audit trigger stores OLD field values but NEW revisionId,
        // so we need to attach attributes from revisionId - 1 (the state BEFORE the update)
        return entries.map { entry ->
            val attributeRevision =
                when (entry.auditType) {
                    AuditType.UPDATE -> entry.revisionId - 1
                    else -> entry.revisionId
                }
            entry.copy(attributes = attributesByRevision[attributeRevision] ?: emptyList())
        }
    }
}
