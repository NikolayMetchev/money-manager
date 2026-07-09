@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.insertStrategy
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class CsvImportStrategyWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: CsvImportStrategyReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportStrategyWriteRepository,
    CsvImportStrategyReadRepository by reader {
    private val selectQueries = database.csvImportStrategySelectQueries
    private val writeQueries = database.csvImportStrategyWriteQueries

    override suspend fun createStrategy(
        strategy: CsvImportStrategy,
        source: Source,
    ): CsvImportStrategyId =
        withContext(coroutineContext) {
            database.transaction {
                writeQueries.insertStrategy(strategy)
                writeQueries.insertSource(
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
                writeQueries.update(
                    name = strategy.name,
                    identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
                    field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
                    attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
                    row_rules_json = FieldMappingJsonCodec.encodeRowRules(strategy.rowPreprocessingRules),
                    companion_rules_json = FieldMappingJsonCodec.encodeCompanionRules(strategy.companionTransactionRules),
                    content_match_rules_json = FieldMappingJsonCodec.encodeContentRules(strategy.contentMatchRules),
                    file_name_pattern = strategy.fileNamePattern,
                    cross_source_reconcile_window_seconds = strategy.crossSourceReconcileWindowSeconds,
                    conversion_config_json = FieldMappingJsonCodec.encodeConversionConfig(strategy.conversionConfig),
                    updated_at = now.toEpochMilliseconds(),
                    id = strategy.id.id.toString(),
                )
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

    override suspend fun deleteStrategy(id: CsvImportStrategyId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id.id.toString())
        }
}
