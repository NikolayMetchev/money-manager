package com.moneymanager.domain.port

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
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

interface CsvStrategyImportExport {
    suspend fun toExport(
        strategy: CsvImportStrategy,
        appVersion: AppVersion,
        accountMappings: List<CsvAccountMapping>? = null,
    ): CsvStrategyExport

    suspend fun parseExport(export: CsvStrategyExport): CsvImportParseResult

    suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): CsvImportStrategy
}
