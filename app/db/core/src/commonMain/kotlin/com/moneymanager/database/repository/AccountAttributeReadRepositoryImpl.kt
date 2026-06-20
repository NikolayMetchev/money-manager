package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountAttributeReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : AccountAttributeReadRepository {
    private val selectQueries = database.accountAttributeSelectQueries

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
}
