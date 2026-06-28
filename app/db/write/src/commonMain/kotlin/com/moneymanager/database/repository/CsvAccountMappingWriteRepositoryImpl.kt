@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvAccountMappingReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class CsvAccountMappingWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: CsvAccountMappingReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvAccountMappingWriteRepository,
    CsvAccountMappingReadRepository by reader {
    private val writeQueries = database.csvAccountMappingWriteQueries

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
}
