package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.write.AccountAttributeCreateInput
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccountAttributeWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    reader: AccountAttributeReadRepository,
) : AccountAttributeWriteRepository,
    AccountAttributeReadRepository by reader {
    private val writeQueries = database.accountAttributeWriteQueries

    override suspend fun insert(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(
                    accountId.id,
                    attributeTypeId.id,
                    value,
                )
                writeQueries.selectLastInsertedId().executeAsOne()
            }
        }

    override suspend fun insertInCreationMode(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                database.beginCreationMode()
                try {
                    writeQueries.insert(
                        accountId.id,
                        attributeTypeId.id,
                        value,
                    )
                    writeQueries.selectLastInsertedId().executeAsOne()
                } finally {
                    database.endCreationMode()
                }
            }
        }

    override suspend fun insertInCreationModeBatch(attributes: List<AccountAttributeCreateInput>): Unit =
        withContext(Dispatchers.Default) {
            if (attributes.isEmpty()) return@withContext
            writeQueries.transaction {
                database.beginCreationMode()
                try {
                    attributes.forEach { input ->
                        writeQueries.insert(
                            input.accountId.id,
                            input.attributeTypeId.id,
                            input.value,
                        )
                    }
                } finally {
                    database.endCreationMode()
                }
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
