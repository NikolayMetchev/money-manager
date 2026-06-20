@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.sql.apiImportStrategy.Api_import_strategy
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.toSourceType
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
    private val selectQueries = database.apiImportStrategySelectQueries
    private val writeQueries = database.apiImportStrategyWriteQueries

    override fun getAllStrategies(): Flow<List<ApiImportStrategy>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map(::toDomain) }

    override fun getStrategyById(id: ApiImportStrategyId): Flow<ApiImportStrategy?> =
        selectQueries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getStrategyByName(name: String): Flow<ApiImportStrategy?> =
        selectQueries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override suspend fun createStrategy(
        strategy: ApiImportStrategy,
        source: Source,
    ): ApiImportStrategyId =
        withContext(coroutineContext) {
            // created_at/updated_at are stamped with the current time by the table's column DEFAULTs,
            // so the database always records when the row was actually persisted (not a domain value).
            writeQueries.insert(
                id = strategy.id.id.toString(),
                name = strategy.name,
                config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
            )
            writeQueries.insertSource(
                strategy_id = strategy.id.id.toString(),
                revision_id = 1,
                source_type_id = source.toSourceType().id.toLong(),
                device_id = deviceId.id,
            )
            strategy.id
        }

    override suspend fun updateStrategy(
        strategy: ApiImportStrategy,
        source: Source,
    ): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            // Wrap the update and its source attribution in a single transaction so the
            // revision_id read back below reflects exactly this update and cannot interleave
            // with a concurrent writer.
            database.transaction {
                writeQueries.update(
                    name = strategy.name,
                    config_json = ApiStrategyJsonCodec.encode(strategy.toConfigJson()),
                    updated_at = now.toEpochMilliseconds(),
                    id = strategy.id.id.toString(),
                )
                // The UPDATE statement increments revision_id in the database, so read the
                // persisted value back instead of deriving it from the (possibly stale) snapshot.
                // This keeps the source row aligned with the audit row the update trigger writes.
                val persistedRevisionId =
                    selectQueries.selectById(strategy.id.id.toString()).executeAsOne().revision_id
                writeQueries.insertSource(
                    strategy_id = strategy.id.id.toString(),
                    revision_id = persistedRevisionId,
                    source_type_id = source.toSourceType().id.toLong(),
                    device_id = deviceId.id,
                )
            }
        }

    override suspend fun deleteStrategy(id: ApiImportStrategyId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id.id.toString())
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
            accountIdentifiersEndpoint = config.accountIdentifiersEndpoint,
            ancestorEndpoints = config.ancestorEndpoints,
            builtInCounterpartyRules = config.builtInCounterpartyRules,
            signing = config.signing,
            peopleDownload = config.peopleDownload,
            personExternalIdAttribute = config.personExternalIdAttribute,
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
            accountIdentifiersEndpoint = accountIdentifiersEndpoint,
            ancestorEndpoints = ancestorEndpoints,
            builtInCounterpartyRules = builtInCounterpartyRules,
            signing = signing,
            peopleDownload = peopleDownload,
            personExternalIdAttribute = personExternalIdAttribute,
        )
}
