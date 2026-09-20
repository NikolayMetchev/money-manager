package com.moneymanager.database

import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.strategy.CsvImportParseResult
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvStrategyImportExport
import com.moneymanager.domain.strategy.CsvStrategyImportResult
import com.moneymanager.domain.strategy.CsvUnresolvedReference

class DbMaintenance(
    private val delegate: DatabaseMaintenanceService,
) : Maintenance {
    override suspend fun reindex() = delegate.reindex()

    override suspend fun vacuum() = delegate.vacuum()

    override suspend fun analyze() = delegate.analyze()

    override suspend fun refreshMaterializedViews() = delegate.refreshMaterializedViews()

    override suspend fun fullRefreshMaterializedViews() = delegate.fullRefreshMaterializedViews()
}

class DbCsvStrategyImportExport(
    private val delegate: CsvStrategyExportService,
) : CsvStrategyImportExport {
    override suspend fun toExport(
        strategy: CsvImportStrategy,
        appVersion: AppVersion,
    ): CsvStrategyExport = delegate.toExport(strategy, appVersion)

    override suspend fun parseExport(export: CsvStrategyExport): CsvImportParseResult = delegate.parseExport(export)

    override suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): CsvStrategyImportResult = delegate.createStrategyFromExport(export, resolutions)
}
