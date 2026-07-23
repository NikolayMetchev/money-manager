package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransferAttributeReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransferAttributeReadRepository {
    private val selectQueries = database.transferAttributeSelectQueries

    override fun getByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>> =
        selectQueries
            .selectByTransaction(transactionId.id)
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
}
