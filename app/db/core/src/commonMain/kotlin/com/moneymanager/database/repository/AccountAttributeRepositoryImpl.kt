package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeCreateInput
import com.moneymanager.domain.repository.AccountAttributeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AccountAttributeRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : AccountAttributeRepository {
    private val selectQueries = database.accountAttributeSelectQueries
    private val writeQueries = database.accountAttributeWriteQueries

    override fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>> =
        selectQueries
            .selectByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    AccountAttribute(
                        id = row.id,
                        accountId = AccountId(row.account_id),
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
