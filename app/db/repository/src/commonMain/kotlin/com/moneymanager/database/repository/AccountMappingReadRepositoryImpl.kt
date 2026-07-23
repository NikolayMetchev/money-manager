package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.accountMapping.Account_mapping
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.repository.AccountMappingReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AccountMappingReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : AccountMappingReadRepository {
    private val selectQueries = database.accountMappingSelectQueries

    override fun getAllMappings(): Flow<List<AccountMapping>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { mappings -> mappings.map(::toDomain) }

    override fun getMappingById(id: Long): Flow<AccountMapping?> =
        selectQueries
            .selectById(id)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    private fun toDomain(entity: Account_mapping): AccountMapping =
        AccountMapping(
            id = entity.id,
            strategyId = entity.strategy_id?.let { CsvImportStrategyId(Uuid.parse(it)) },
            valuePattern = Regex(entity.value_pattern, RegexOption.IGNORE_CASE),
            accountId = AccountId(entity.account_id),
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
