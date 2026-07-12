package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.TradeMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TradeReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TradeReadRepository {
    private val selectQueries = database.tradeSelectQueries

    override fun getTradeById(id: TradeId): Flow<Trade?> =
        selectQueries
            .selectById(id.id, TradeMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)

    override fun getTradesByAccount(accountId: AccountId): Flow<List<Trade>> =
        selectQueries
            .selectByAccount(accountId.id, accountId.id, TradeMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)

    override suspend fun countTradesByAccount(accountId: AccountId): Long =
        withContext(Dispatchers.Default) {
            selectQueries.countByAccount(accountId.id).executeAsOne()
        }

    override suspend fun accountsWithTrades(accountIds: Collection<AccountId>): Set<AccountId> =
        withContext(Dispatchers.Default) {
            accountIds
                .asSequence()
                .map { it.id }
                .distinct()
                .chunked(MAX_IDS_PER_TWO_SIDED_QUERY)
                .flatMap { chunk ->
                    selectQueries.selectAccountsWithTrades(chunk).executeAsList()
                }.mapTo(mutableSetOf(), ::AccountId)
        }
}
