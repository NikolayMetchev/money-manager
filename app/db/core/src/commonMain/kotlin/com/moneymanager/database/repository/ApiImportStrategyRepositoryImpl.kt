@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiImportStrategyRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ApiImportStrategyRepository {
    private val queries = database.apiImportStrategyQueries

    override fun getAllStrategies(): Flow<List<ApiImportStrategy>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map(::toDomain) }

    override fun getStrategyById(id: ApiImportStrategyId): Flow<ApiImportStrategy?> =
        queries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getStrategyByName(name: String): Flow<ApiImportStrategy?> =
        queries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override suspend fun createStrategy(strategy: ApiImportStrategy): ApiImportStrategyId =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            queries.insert(
                id = strategy.id.id.toString(),
                name = strategy.name,
                config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
            strategy.id
        }

    override suspend fun updateStrategy(strategy: ApiImportStrategy): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            queries.update(
                name = strategy.name,
                config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
                updated_at = now.toEpochMilliseconds(),
                id = strategy.id.id.toString(),
            )
        }

    override suspend fun deleteStrategy(id: ApiImportStrategyId): Unit =
        withContext(coroutineContext) {
            queries.deleteById(id.id.toString())
        }

    private fun toDomain(entity: com.moneymanager.database.sql.Api_import_strategy): ApiImportStrategy {
        val config = ApiStrategyJsonCodec.decode(entity.config_json)
        return ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.parse(entity.id)),
            name = entity.name,
            baseUrl = config.baseUrl,
            authType = config.authType,
            accountsEndpoint = config.accountsEndpoint,
            transactionsEndpoint = config.transactionsEndpoint,
            accountMappings = config.accountMappings,
            transactionMappings = config.transactionMappings,
            accountNamePrefix = config.accountNamePrefix,
            counterpartyPrefix = config.counterpartyPrefix,
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
    }

    private fun ApiImportStrategy.toConfigJson(): ApiStrategyConfigJson =
        ApiStrategyConfigJson(
            baseUrl = baseUrl,
            authType = authType,
            accountsEndpoint = accountsEndpoint,
            transactionsEndpoint = transactionsEndpoint,
            accountMappings = accountMappings,
            transactionMappings = transactionMappings,
            accountNamePrefix = accountNamePrefix,
            counterpartyPrefix = counterpartyPrefix,
        )
}
