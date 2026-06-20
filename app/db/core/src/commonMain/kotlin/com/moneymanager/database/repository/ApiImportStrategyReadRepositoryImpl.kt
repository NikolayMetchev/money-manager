@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.database.sql.apiImportStrategy.Api_import_strategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiImportStrategyReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ApiImportStrategyReadRepository {
    private val selectQueries = database.apiImportStrategySelectQueries

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
}
