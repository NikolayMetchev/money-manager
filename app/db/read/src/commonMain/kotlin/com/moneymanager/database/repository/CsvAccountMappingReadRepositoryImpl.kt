@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvAccountMappingReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvAccountMappingReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvAccountMappingReadRepository {
    private val selectQueries = database.csvAccountMappingSelectQueries

    override fun getMappingsForStrategy(strategyId: CsvImportStrategyId): Flow<List<CsvAccountMapping>> =
        selectQueries
            .selectByStrategyId(strategyId.id.toString())
            .asFlow()
            .mapToList(coroutineContext)
            .map { mappings -> mappings.map(::toDomain) }

    override fun getMappingById(id: Long): Flow<CsvAccountMapping?> =
        selectQueries
            .selectById(id)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    private fun toDomain(entity: com.moneymanager.database.sql.csvAccountMapping.Csv_account_mapping): CsvAccountMapping =
        CsvAccountMapping(
            id = entity.id,
            strategyId = CsvImportStrategyId(Uuid.parse(entity.strategy_id)),
            columnName = entity.column_name,
            valuePattern = Regex(entity.value_pattern, RegexOption.IGNORE_CASE),
            accountId = AccountId(entity.account_id),
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
