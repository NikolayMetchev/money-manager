package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.ExchangeOrderMapper
import com.moneymanager.database.mapper.TradeMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ExchangeOrder
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExchangeOrderReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : ExchangeOrderReadRepository {
    private val selectQueries = database.exchangeOrderSelectQueries

    override fun getOrderById(id: ExchangeOrderId): Flow<ExchangeOrder?> =
        selectQueries
            .selectById(id.id, ExchangeOrderMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)

    override fun getOrdersByAccount(accountId: AccountId): Flow<List<ExchangeOrder>> =
        selectQueries
            .selectByAccount(accountId.id, ExchangeOrderMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)

    override fun countOrdersByAccount(accountId: AccountId): Flow<Long> =
        selectQueries
            .countByAccount(accountId.id)
            .asFlow()
            .mapToOne(Dispatchers.Default)

    override fun getFillTradesForOrder(id: ExchangeOrderId): Flow<List<Trade>> =
        selectQueries
            .selectFillTradesForOrder(id.id, TradeMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)

    override fun getFillCountsByAccount(accountId: AccountId): Flow<Map<ExchangeOrderId, Long>> =
        selectQueries
            .selectFillCountsByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.associate { ExchangeOrderId(it.order_id) to it.fill_count } }
}
