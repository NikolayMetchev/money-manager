@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeAuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

class TransferAttributeAuditRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : TransferAttributeAuditRepository {
    private val queries = database.transferAttributeAuditQueries

    override fun getByTransactionAndRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): Flow<List<TransferAttributeAuditEntry>> =
        queries.selectByTransactionAndRevision(transactionId.id.toString(), revisionId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
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
            }

    override fun getAllByTransaction(transactionId: TransferId): Flow<List<TransferAttributeAuditEntry>> =
        queries.selectAllByTransaction(transactionId.id.toString())
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
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
            }

    private fun mapAuditType(name: String): AuditType =
        when (name) {
            "INSERT" -> AuditType.INSERT
            "UPDATE" -> AuditType.UPDATE
            "DELETE" -> AuditType.DELETE
            else -> error("Unknown audit type: $name")
        }
}
