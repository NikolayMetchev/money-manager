@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeReadRepository
import com.moneymanager.domain.repository.TransferAttributeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransferAttributeWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: TransferAttributeReadRepository,
) : TransferAttributeWriteRepository,
    TransferAttributeReadRepository by reader {
    private val writeQueries = database.transferAttributeWriteQueries

    override suspend fun insert(
        transactionId: TransferId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(
                    transactionId.id,
                    attributeTypeId.id,
                    value,
                )
                // Get the inserted ID using last_insert_rowid()
                writeQueries.selectLastInsertedId().executeAsOne()
            }
        }

    override suspend fun updateValue(
        id: Long,
        newValue: String,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.updateValue(newValue, id)
        }

    override suspend fun delete(id: Long): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.deleteById(id)
        }
}
