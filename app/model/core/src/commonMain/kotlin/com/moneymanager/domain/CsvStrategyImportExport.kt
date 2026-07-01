package com.moneymanager.domain

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport

enum class CsvReferenceType {
    ACCOUNT,
    CURRENCY,
    CATEGORY,
}

data class CsvUnresolvedReference(
    val type: CsvReferenceType,
    val name: String,
    val fieldType: TransferField?,
)

sealed interface CsvResolution {
    data class MapToExisting(
        val id: Long,
    ) : CsvResolution

    data class MapToExistingCurrency(
        val id: String,
    ) : CsvResolution

    data class CreateNew(
        val name: String,
    ) : CsvResolution
}

data class CsvImportParseResult(
    val strategyName: String,
    val export: CsvStrategyExport,
    val unresolvedReferences: List<CsvUnresolvedReference>,
)

/**
 * Outcome of building a strategy from an export: the (not-yet-persisted) strategy plus its resolved
 * per-strategy account mappings (already scoped to [CsvImportStrategy.id], which is stable across
 * persistence). The caller persists the strategy first, then the mappings (which FK-reference it).
 */
data class CsvStrategyImportResult(
    val strategy: CsvImportStrategy,
    val accountMappings: List<AccountMapping>,
)

interface CsvStrategyImportExport {
    suspend fun toExport(
        strategy: CsvImportStrategy,
        appVersion: AppVersion,
    ): CsvStrategyExport

    suspend fun parseExport(export: CsvStrategyExport): CsvImportParseResult

    suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): CsvStrategyImportResult
}
