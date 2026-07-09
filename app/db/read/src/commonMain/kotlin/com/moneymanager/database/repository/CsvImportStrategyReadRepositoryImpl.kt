@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportStrategyReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportStrategyReadRepository {
    private val selectQueries = database.csvImportStrategySelectQueries

    override fun getAllStrategies(): Flow<List<CsvImportStrategy>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { strategies -> strategies.map(::toDomain) }

    override fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?> =
        selectQueries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getStrategyByName(name: String): Flow<CsvImportStrategy?> =
        selectQueries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    private fun toDomain(entity: com.moneymanager.database.sql.csvImportStrategy.Csv_import_strategy): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(entity.id)),
            name = entity.name,
            identificationColumns = FieldMappingJsonCodec.decodeColumns(entity.identification_columns_json),
            fieldMappings = FieldMappingJsonCodec.decode(entity.field_mappings_json),
            attributeMappings = FieldMappingJsonCodec.decodeAttributeMappings(entity.attribute_mappings_json),
            rowPreprocessingRules = FieldMappingJsonCodec.decodeRowRules(entity.row_rules_json),
            companionTransactionRules = FieldMappingJsonCodec.decodeCompanionRules(entity.companion_rules_json),
            contentMatchRules = FieldMappingJsonCodec.decodeContentRules(entity.content_match_rules_json),
            fileNamePattern = entity.file_name_pattern,
            crossSourceReconcileWindowSeconds = entity.cross_source_reconcile_window_seconds,
            conversionConfig = FieldMappingJsonCodec.decodeConversionConfig(entity.conversion_config_json),
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
