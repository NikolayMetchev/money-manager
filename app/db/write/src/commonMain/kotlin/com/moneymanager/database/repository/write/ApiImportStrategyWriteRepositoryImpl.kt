@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.write.ApiImportStrategyWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class ApiImportStrategyWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: ApiImportStrategyReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ApiImportStrategyWriteRepository,
    ApiImportStrategyReadRepository by reader {
    private val selectQueries = database.apiImportStrategySelectQueries
    private val writeQueries = database.apiImportStrategyWriteQueries

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

    private fun ApiImportStrategy.toConfigJson(): ApiStrategyConfigJson =
        ApiStrategyConfigJson(
            baseUrl = baseUrl,
            authType = authType,
            accountsEndpoint = accountsEndpoint,
            transactionsEndpoint = transactionsEndpoint,
            accountMappings = accountMappings,
            transactionMappings = transactionMappings,
            peopleMappings = peopleMappings,
            accountIdentifiersEndpoint = accountIdentifiersEndpoint,
            ancestorEndpoints = ancestorEndpoints,
            builtInCounterpartyRules = builtInCounterpartyRules,
            signing = signing,
            peopleDownload = peopleDownload,
            personExternalIdAttribute = personExternalIdAttribute,
            requestSigning = requestSigning,
            dataEndpoints = dataEndpoints,
            syntheticAccount = syntheticAccount,
            internalTransferReconcile = internalTransferReconcile,
            assetAliases = assetAliases,
            tokenPageUrl = tokenPageUrl,
            connectInstructions = connectInstructions,
        )
}
