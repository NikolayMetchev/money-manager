package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ExchangeOrder
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Trade
import kotlinx.coroutines.flow.Flow

interface ExchangeOrderReadRepository {
    fun getOrderById(id: ExchangeOrderId): Flow<ExchangeOrder?>

    /** Orders placed on [accountId], newest first. */
    fun getOrdersByAccount(accountId: AccountId): Flow<List<ExchangeOrder>>

    /** Number of orders on [accountId] (drives visibility of the Orders UI entry point). */
    fun countOrdersByAccount(accountId: AccountId): Flow<Long>

    /** The fill trades linked to [id] via `exchange_order_trade`, oldest first. */
    fun getFillTradesForOrder(id: ExchangeOrderId): Flow<List<Trade>>

    /** Fill-trade counts per order across [accountId]'s orders (single query for list rendering). */
    fun getFillCountsByAccount(accountId: AccountId): Flow<Map<ExchangeOrderId, Long>>
}
