package com.moneymanager.database.repository.write

import com.moneymanager.database.json.CsvStrategyJsonCodec
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.insertStrategy
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.write.CsvImportStrategyWriteRepository
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
                    config_json = CsvStrategyJsonCodec.encode(strategy.config),
                    updated_at = now.toEpochMilliseconds(),
                    id = strategy.id.id.toString(),
                )
                val worksheetName = strategy.worksheetName
                if (worksheetName != null) {
                    writeQueries.insertOrReplaceXlsxWorksheet(
                        csv_import_strategy_id = strategy.id.id.toString(),
                        worksheet_name = worksheetName,
                    )
                } else {
                    writeQueries.deleteXlsxWorksheet(strategy.id.id.toString())
                }
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
