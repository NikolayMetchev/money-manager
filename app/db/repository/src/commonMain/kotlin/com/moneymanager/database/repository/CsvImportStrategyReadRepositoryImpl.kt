package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.json.CsvStrategyJsonCodec
import com.moneymanager.database.sql.csvImportStrategy.Csv_import_strategy_with_worksheet
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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
            .map { rows -> rows.map(::toDomain) }
            .flowOn(coroutineContext)

    override fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?> =
        selectQueries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }
            .flowOn(coroutineContext)

    override fun getStrategyByName(name: String): Flow<CsvImportStrategy?> =
        selectQueries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }
            .flowOn(coroutineContext)

    // Decoding config_json is real work, so each mapping above runs in coroutineContext via flowOn rather
    // than in the collector's (often the UI's) context.
    private fun toDomain(entity: Csv_import_strategy_with_worksheet): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(entity.id)),
            name = entity.name,
            config = CsvStrategyJsonCodec.decode(entity.config_json),
            worksheetName = entity.worksheet_name,
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )
}
