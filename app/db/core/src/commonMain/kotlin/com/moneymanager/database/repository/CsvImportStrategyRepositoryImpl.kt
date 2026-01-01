@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
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
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportStrategyRepository {
    private val queries = database.csvImportStrategyQueries

    override fun getAllStrategies(): Flow<List<CsvImportStrategy>> =
        queries.selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { strategies -> strategies.map(::toDomain) }

    override fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?> =
        queries.selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getStrategyByName(name: String): Flow<CsvImportStrategy?> =
        queries.selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override suspend fun findMatchingStrategy(headings: Set<String>): CsvImportStrategy? =
        withContext(coroutineContext) {
            val strategies = getAllStrategies().first()
            strategies.find { it.matchesColumns(headings) }
        }

    override suspend fun createStrategy(strategy: CsvImportStrategy): CsvImportStrategyId =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            queries.insert(
                id = strategy.id.id.toString(),
                name = strategy.name,
                identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
                field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
                attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
            strategy.id
        }

    override suspend fun updateStrategy(strategy: CsvImportStrategy): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            queries.update(
                name = strategy.name,
                identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
                field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
                attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
                updated_at = now.toEpochMilliseconds(),
                id = strategy.id.id.toString(),
            )
        }

    override suspend fun deleteStrategy(id: CsvImportStrategyId): Unit =
        withContext(coroutineContext) {
            queries.deleteById(id.id.toString())
        }

    private fun toDomain(entity: com.moneymanager.database.sql.CsvImportStrategy): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(entity.id)),
            name = entity.name,
            identificationColumns = FieldMappingJsonCodec.decodeColumns(entity.identification_columns_json),
            fieldMappings = FieldMappingJsonCodec.decode(entity.field_mappings_json),
            attributeMappings = FieldMappingJsonCodec.decodeAttributeMappings(entity.attribute_mappings_json),
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
