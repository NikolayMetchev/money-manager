package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeReadRepository
import com.moneymanager.domain.repository.write.TransferAttributeWriteRepository
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
        groupKey: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(
                    transactionId.id,
                    attributeTypeId.id,
                    value,
                    groupKey,
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
