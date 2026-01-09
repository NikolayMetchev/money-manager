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

class TransferAttributeRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : TransferAttributeRepository {
    private val queries = database.transferAttributeQueries

    override fun getByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>> =
        queries.selectByTransaction(transactionId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    TransferAttribute(
                        id = row.id,
                        transactionId = TransferId(row.transaction_id),
                        attributeType =
                            AttributeType(
                                id = AttributeTypeId(row.attribute_type_id),
                                name = row.attribute_type_name,
                            ),
                        value = row.attribute_value,
                    )
                }
            }

    override suspend fun insert(
        transactionId: TransferId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.insert(
                    transactionId.id,
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
}
