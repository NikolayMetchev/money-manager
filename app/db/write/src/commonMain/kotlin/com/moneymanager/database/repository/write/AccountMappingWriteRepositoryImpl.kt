package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.write.AccountMappingWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class AccountMappingWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: AccountMappingReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : AccountMappingWriteRepository,
    AccountMappingReadRepository by reader {
    private val writeQueries = database.accountMappingWriteQueries

    override suspend fun createMapping(
        valuePattern: Regex,
        accountId: AccountId,
        strategyId: CsvImportStrategyId?,
    ): Long =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            writeQueries.insert(
                strategy_id = strategyId?.id?.toString(),
                value_pattern = valuePattern.pattern,
                account_id = accountId.id,
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
            writeQueries.lastInsertRowId().executeAsOne()
        }

    override suspend fun createMappings(mappings: List<AccountMapping>): Unit =
        withContext(coroutineContext) {
            if (mappings.isEmpty()) return@withContext

            writeQueries.transaction {
                mappings.forEach { mapping ->
                    writeQueries.insert(
                        strategy_id = mapping.strategyId?.id?.toString(),
                        value_pattern = mapping.valuePattern.pattern,
                        account_id = mapping.accountId.id,
                        created_at = mapping.createdAt.toEpochMilliseconds(),
                        updated_at = mapping.updatedAt.toEpochMilliseconds(),
                    )
                }
            }
        }

    override suspend fun updateMapping(mapping: AccountMapping): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            writeQueries.update(
                strategy_id = mapping.strategyId?.id?.toString(),
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
}
