@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class TransferAttributeRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : TransferAttributeRepository {
    private val queries = database.transferAttributeQueries

    override fun getByTransactionAndRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): Flow<List<TransferAttribute>> =
        queries.selectByTransactionAndRevision(transactionId.id.toString(), revisionId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
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
            }

    override fun getAllByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>> =
        queries.selectAllByTransaction(transactionId.id.toString())
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
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
            }

    override suspend fun insert(
        transactionId: TransferId,
        revisionId: Long,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.insert(
                    transactionId.id.toString(),
                    revisionId,
                    attributeTypeId.id,
                    value,
                )
                // Get the inserted ID using last_insert_rowid()
                queries.selectLastInsertedId().executeAsOne()
            }
        }

    override suspend fun updateValue(
        id: Long,
        newValue: String,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.updateValue(newValue, id)
        }

    override suspend fun delete(id: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.deleteById(id)
        }

    override suspend fun insertBatch(
        transactionId: TransferId,
        revisionId: Long,
        attributes: List<Pair<AttributeTypeId, String>>,
    ): Unit =
        withContext(Dispatchers.Default) {
            if (attributes.isEmpty()) return@withContext

            database.transaction {
                // Enable batch mode - triggers will skip
                database.execute(null, "INSERT INTO _import_batch VALUES (1)", 0)

                try {
                    attributes.forEach { (attributeTypeId, value) ->
                        queries.insert(
                            transactionId.id.toString(),
                            revisionId,
                            attributeTypeId.id,
                            value,
                        )
                    }
                } finally {
                    // Disable batch mode
                    database.execute(null, "DELETE FROM _import_batch", 0)
                }
            }
        }
}
