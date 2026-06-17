@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.insertStrategy
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportStrategyRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportStrategyRepository {
    private val queries = database.csvImportStrategyQueries

    override fun getAllStrategies(): Flow<List<CsvImportStrategy>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { strategies -> strategies.map(::toDomain) }

    override fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?> =
        queries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getStrategyByName(name: String): Flow<CsvImportStrategy?> =
        queries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override suspend fun findMatchingStrategy(headings: Set<String>): CsvImportStrategy? =
        withContext(coroutineContext) {
            val strategies = getAllStrategies().first()
            strategies.find { it.matchesColumns(headings) }
        }

    override suspend fun createStrategy(
        strategy: CsvImportStrategy,
        source: Source,
    ): CsvImportStrategyId =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            database.transaction {
                queries.insertStrategy(strategy, now)
                queries.insertSource(
                    strategy_id = strategy.id.id.toString(),
                    revision_id = 1,
                    source_type_id = source.toSourceType().id.toLong(),
                    device_id = deviceId.id,
                )
            }
            strategy.id
        }

    override suspend fun updateStrategy(
        strategy: CsvImportStrategy,
        source: Source,
    ): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            // Wrap the update and its source attribution in a single transaction so the revision_id read
            // back below reflects exactly this update and cannot interleave with a concurrent writer.
            database.transaction {
                queries.update(
                    name = strategy.name,
                    identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
                    field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
                    attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
                    row_rules_json = FieldMappingJsonCodec.encodeRowRules(strategy.rowPreprocessingRules),
                    companion_rules_json = FieldMappingJsonCodec.encodeCompanionRules(strategy.companionTransactionRules),
                    content_match_rules_json = FieldMappingJsonCodec.encodeContentRules(strategy.contentMatchRules),
                    updated_at = now.toEpochMilliseconds(),
                    id = strategy.id.id.toString(),
                )
                val persistedRevisionId =
                    queries.selectById(strategy.id.id.toString()).executeAsOne().revision_id
                queries.insertSource(
                    strategy_id = strategy.id.id.toString(),
                    revision_id = persistedRevisionId,
                    source_type_id = source.toSourceType().id.toLong(),
                    device_id = deviceId.id,
                )
            }
        }

    override suspend fun deleteStrategy(id: CsvImportStrategyId): Unit =
        withContext(coroutineContext) {
            queries.deleteById(id.id.toString())
        }

    private fun toDomain(entity: com.moneymanager.database.sql.Csv_import_strategy): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(entity.id)),
            name = entity.name,
            identificationColumns = FieldMappingJsonCodec.decodeColumns(entity.identification_columns_json),
            fieldMappings = FieldMappingJsonCodec.decode(entity.field_mappings_json),
            attributeMappings = FieldMappingJsonCodec.decodeAttributeMappings(entity.attribute_mappings_json),
            rowPreprocessingRules = FieldMappingJsonCodec.decodeRowRules(entity.row_rules_json),
            companionTransactionRules = FieldMappingJsonCodec.decodeCompanionRules(entity.companion_rules_json),
            contentMatchRules = FieldMappingJsonCodec.decodeContentRules(entity.content_match_rules_json),
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
