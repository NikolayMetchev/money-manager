@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.sql.Api_import_strategy
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.SourceType
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
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
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
            // Use the current time as the authoritative creation timestamp rather than the one
            // supplied in the domain object. This mirrors CsvImportStrategyRepositoryImpl and
            // ensures the database always records when the row was actually persisted.
            val now = Clock.System.now()
            queries.insert(
                id = strategy.id.id.toString(),
                name = strategy.name,
                config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
            queries.insertSource(
                strategy_id = strategy.id.id.toString(),
                revision_id = 1,
                source_type_id = SourceType.MANUAL.id.toLong(),
                device_id = deviceId.id,
            )
            strategy.id
        }

    override suspend fun updateStrategy(strategy: ApiImportStrategy): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            // Wrap the update and its source attribution in a single transaction so the
            // revision_id read back below reflects exactly this update and cannot interleave
            // with a concurrent writer.
            database.transaction {
                queries.update(
                    name = strategy.name,
                    config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
                    updated_at = now.toEpochMilliseconds(),
                    id = strategy.id.id.toString(),
                )
                // The UPDATE statement increments revision_id in the database, so read the
                // persisted value back instead of deriving it from the (possibly stale) snapshot.
                // This keeps the source row aligned with the audit row the update trigger writes.
                val persistedRevisionId =
                    queries.selectById(strategy.id.id.toString()).executeAsOne().revision_id
                queries.insertSource(
                    strategy_id = strategy.id.id.toString(),
                    revision_id = persistedRevisionId,
                    source_type_id = SourceType.MANUAL.id.toLong(),
                    device_id = deviceId.id,
                )
            }
        }

    override suspend fun deleteStrategy(id: ApiImportStrategyId): Unit =
        withContext(coroutineContext) {
            queries.deleteById(id.id.toString())
        }

    private fun toDomain(entity: Api_import_strategy): ApiImportStrategy {
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
            peopleMappings = config.peopleMappings,
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
            revisionId = entity.revision_id,
            configJson = entity.config_json,
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
            peopleMappings = peopleMappings,
        )
}
