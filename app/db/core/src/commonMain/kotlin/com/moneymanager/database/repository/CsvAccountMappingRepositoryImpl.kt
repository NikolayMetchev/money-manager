@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvAccountMappingRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvAccountMappingRepository {
    private val selectQueries = database.csvAccountMappingSelectQueries
    private val writeQueries = database.csvAccountMappingWriteQueries

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

    override suspend fun createMapping(
        strategyId: CsvImportStrategyId,
        columnName: String,
        valuePattern: Regex,
        accountId: AccountId,
    ): Long =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            writeQueries.insert(
                strategy_id = strategyId.id.toString(),
                column_name = columnName,
                value_pattern = valuePattern.pattern,
                account_id = accountId.id,
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
            writeQueries.lastInsertRowId().executeAsOne()
        }

    override suspend fun createMappings(mappings: List<CsvAccountMapping>): Unit =
        withContext(coroutineContext) {
            if (mappings.isEmpty()) return@withContext

            writeQueries.transaction {
                mappings.forEach { mapping ->
                    writeQueries.insert(
                        strategy_id = mapping.strategyId.id.toString(),
                        column_name = mapping.columnName,
                        value_pattern = mapping.valuePattern.pattern,
                        account_id = mapping.accountId.id,
                        created_at = mapping.createdAt.toEpochMilliseconds(),
                        updated_at = mapping.updatedAt.toEpochMilliseconds(),
                    )
                }
            }
        }

    override suspend fun updateMapping(mapping: CsvAccountMapping): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            writeQueries.update(
                column_name = mapping.columnName,
                value_pattern = mapping.valuePattern.pattern,
                account_id = mapping.accountId.id,
                updated_at = now.toEpochMilliseconds(),
                id = mapping.id,
            )
        }

    override suspend fun deleteMapping(id: Long): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id)
        }

    override suspend fun deleteMappingsForStrategy(strategyId: CsvImportStrategyId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteByStrategyId(strategyId.id.toString())
        }

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
