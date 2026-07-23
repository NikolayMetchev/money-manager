package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
            .selectAll(::toDomain)
            .asFlow()
            .mapToList(coroutineContext)

    override fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?> =
        selectQueries
            .selectById(id.id.toString(), ::toDomain)
            .asFlow()
            .mapToOneOrNull(coroutineContext)

    override fun getStrategyByName(name: String): Flow<CsvImportStrategy?> =
        selectQueries
            .selectByName(name, ::toDomain)
            .asFlow()
            .mapToOneOrNull(coroutineContext)

    // revisionId is part of the query's column set (needed to keep this a positional match for the
    // generated mapper) but isn't part of the domain model.
    @Suppress("LongParameterList", "UnusedParameter")
    private fun toDomain(
        id: String,
        revisionId: Long,
        name: String,
        identificationColumnsJson: String,
        fieldMappingsJson: String,
        attributeMappingsJson: String,
        rowRulesJson: String,
        companionRulesJson: String,
        contentMatchRulesJson: String,
        fileNamePattern: String?,
        crossSourceReconcileWindowSeconds: Long?,
        conversionConfigJson: String?,
        fundingAttributeMatchJson: String?,
        createdAt: Long,
        updatedAt: Long,
        worksheetName: String?,
    ): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(id)),
            name = name,
            identificationColumns = FieldMappingJsonCodec.decodeColumns(identificationColumnsJson),
            fieldMappings = FieldMappingJsonCodec.decode(fieldMappingsJson),
            attributeMappings = FieldMappingJsonCodec.decodeAttributeMappings(attributeMappingsJson),
            rowPreprocessingRules = FieldMappingJsonCodec.decodeRowRules(rowRulesJson),
            companionTransactionRules = FieldMappingJsonCodec.decodeCompanionRules(companionRulesJson),
            contentMatchRules = FieldMappingJsonCodec.decodeContentRules(contentMatchRulesJson),
            fileNamePattern = fileNamePattern,
            crossSourceReconcileWindowSeconds = crossSourceReconcileWindowSeconds,
            conversionConfig = FieldMappingJsonCodec.decodeConversionConfig(conversionConfigJson),
            fundingAttributeMatch = FieldMappingJsonCodec.decodeAttributeAccountMatch(fundingAttributeMatchJson),
            worksheetName = worksheetName,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        )
}
