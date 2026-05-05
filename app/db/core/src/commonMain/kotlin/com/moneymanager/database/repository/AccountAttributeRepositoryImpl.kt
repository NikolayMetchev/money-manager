package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AccountAttributeRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : AccountAttributeRepository {
    private val queries = database.accountAttributeQueries

    override fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>> =
        queries
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
            queries.transactionWithResult {
                queries.insert(
                    accountId.id,
                    attributeTypeId.id,
                    value,
                )
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
