package com.moneymanager.database.port

import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.CsvImportSourceRecorder
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.QifImportSourceRecorder
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.service.ImportParseResult
import com.moneymanager.database.service.ReferenceType
import com.moneymanager.database.service.Resolution
import com.moneymanager.database.service.UnresolvedReference
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.CsvImportParseResult
import com.moneymanager.domain.CsvReferenceType
import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.qif.QifImportId

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
        accountMappings: List<CsvAccountMapping>?,
    ): CsvStrategyExport = delegate.toExport(strategy, appVersion, accountMappings)

    override suspend fun parseExport(export: CsvStrategyExport): CsvImportParseResult = delegate.parseExport(export).toDomain()

    override suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): CsvImportStrategy = delegate.createStrategyFromExport(export, resolutions.toDb())
}

class DbEntitySource(
    private val transferSourceQueries: TransferSourceQueries,
    override val deviceId: DeviceId,
) : EntitySource {
    override fun manualRecorder(): SourceRecorder = ManualSourceRecorder(transferSourceQueries, deviceId)

    override fun sampleGeneratorRecorder(): SourceRecorder = SampleGeneratorSourceRecorder(transferSourceQueries, deviceId)

    override fun csvImportRecorder(
        csvImportId: CsvImportId,
        rowIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder =
        CsvImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            csvImportId = csvImportId,
            rowIndexForTransfer = { transferId -> rowIndexForTransfer(transferId) },
        )

    override fun apiImportRecorder(
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    ): SourceRecorder =
        ApiImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            sessionId = sessionId,
            requestId = requestId,
            jsonPath = jsonPath,
        )

    override fun qifImportRecorder(
        qifImportId: QifImportId,
        recordIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder =
        QifImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            qifImportId = qifImportId,
            recordIndexForTransfer = { transferId -> recordIndexForTransfer(transferId) },
        )
}

class DbSampleEntitySource(
    private val transferSourceQueries: TransferSourceQueries,
    override val deviceId: DeviceId,
) : EntitySource {
    override fun manualRecorder(): SourceRecorder = ManualSourceRecorder(transferSourceQueries, deviceId)

    override fun sampleGeneratorRecorder(): SourceRecorder = SampleGeneratorSourceRecorder(transferSourceQueries, deviceId)

    override fun csvImportRecorder(
        csvImportId: CsvImportId,
        rowIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder =
        CsvImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            csvImportId = csvImportId,
            rowIndexForTransfer = { transferId -> rowIndexForTransfer(transferId) },
        )

    override fun apiImportRecorder(
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    ): SourceRecorder =
        ApiImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            sessionId = sessionId,
            requestId = requestId,
            jsonPath = jsonPath,
        )

    override fun qifImportRecorder(
        qifImportId: QifImportId,
        recordIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder =
        QifImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            qifImportId = qifImportId,
            recordIndexForTransfer = { transferId -> recordIndexForTransfer(transferId) },
        )
}

private fun ImportParseResult.toDomain() =
    CsvImportParseResult(
        strategyName = strategyName,
        export = export,
        unresolvedReferences = unresolvedReferences.map { it.toDomain() },
    )

private fun UnresolvedReference.toDomain() =
    CsvUnresolvedReference(
        type =
            when (type) {
                ReferenceType.ACCOUNT -> CsvReferenceType.ACCOUNT
                ReferenceType.CURRENCY -> CsvReferenceType.CURRENCY
                ReferenceType.CATEGORY -> CsvReferenceType.CATEGORY
            },
        name = name,
        fieldType = fieldType,
    )

private fun Map<CsvUnresolvedReference, CsvResolution>.toDb(): Map<UnresolvedReference, Resolution> =
    entries.associate { (key, value) ->
        key.toDb() to value.toDb()
    }

private fun CsvUnresolvedReference.toDb() =
    UnresolvedReference(
        type =
            when (type) {
                CsvReferenceType.ACCOUNT -> ReferenceType.ACCOUNT
                CsvReferenceType.CURRENCY -> ReferenceType.CURRENCY
                CsvReferenceType.CATEGORY -> ReferenceType.CATEGORY
            },
        name = name,
        fieldType = fieldType,
    )

private fun CsvResolution.toDb(): Resolution =
    when (this) {
        is CsvResolution.CreateNew -> Resolution.CreateNew(name)
        is CsvResolution.MapToExisting -> Resolution.MapToExisting(id)
        is CsvResolution.MapToExistingCurrency -> Resolution.MapToExistingCurrency(id)
    }
