package com.moneymanager.database.port

import com.moneymanager.database.ApiEntitySourceRecorder
import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.CsvImportSourceRecorder
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.ManualEntitySourceRecorder
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.SampleGeneratorEntitySourceRecorder
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.service.ImportParseResult
import com.moneymanager.database.service.ReferenceType
import com.moneymanager.database.service.Resolution
import com.moneymanager.database.service.UnresolvedReference
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.ApiEntitySourceRecord
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
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport

private const val API_ENTITY_SOURCE_REVISION_ID = 1L

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
    private val entitySourceQueries: EntitySourceQueries,
    private val transferSourceQueries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : EntitySource {
    private val recorder = ManualEntitySourceRecorder(entitySourceQueries, deviceId)

    override fun record(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        recorder.insert(entityType, entityId, revisionId)
    }

    override fun recordFromApi(record: ApiEntitySourceRecord) {
        ApiEntitySourceRecorder(
            entitySourceQueries,
            deviceId,
            record.sessionId,
            record.requestId,
            record.jsonPath,
        ).insert(record.entityType, record.entityId, API_ENTITY_SOURCE_REVISION_ID)
    }

    override fun recordFromApiBatch(records: List<ApiEntitySourceRecord>) {
        recordFromApiBatchInternal(entitySourceQueries, deviceId, records)
    }

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
}

class DbSampleEntitySource(
    private val entitySourceQueries: EntitySourceQueries,
    private val transferSourceQueries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : EntitySource {
    private val recorder = SampleGeneratorEntitySourceRecorder(entitySourceQueries, deviceId)

    override fun record(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        recorder.insert(entityType, entityId, revisionId)
    }

    override fun recordFromApi(record: ApiEntitySourceRecord) {
        ApiEntitySourceRecorder(
            entitySourceQueries,
            deviceId,
            record.sessionId,
            record.requestId,
            record.jsonPath,
        ).insert(record.entityType, record.entityId, API_ENTITY_SOURCE_REVISION_ID)
    }

    override fun recordFromApiBatch(records: List<ApiEntitySourceRecord>) {
        recordFromApiBatchInternal(entitySourceQueries, deviceId, records)
    }

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

private fun recordFromApiBatchInternal(
    queries: EntitySourceQueries,
    deviceId: DeviceId,
    records: List<ApiEntitySourceRecord>,
) {
    if (records.isEmpty()) return
    queries.transaction {
        records.forEach { record ->
            queries.insertSource(
                entity_type_id = record.entityType.id,
                entity_id = record.entityId,
                revision_id = API_ENTITY_SOURCE_REVISION_ID,
                source_type_id = SourceType.API.id.toLong(),
                device_id = deviceId.id,
            )
            val entitySource =
                queries
                    .selectEntitySourceForRevision(
                        entity_type_id = record.entityType.id,
                        entity_id = record.entityId,
                        revision_id = API_ENTITY_SOURCE_REVISION_ID,
                    ).executeAsOne()
            if (entitySource.source_type_id != SourceType.API.id.toLong()) return@forEach
            val entitySourceId = entitySource.id
            if (queries.selectApiEntitySourceId(id = entitySourceId).executeAsOneOrNull() != null) return@forEach
            queries.insertApiSource(
                id = entitySourceId,
                api_session_id = record.sessionId.id,
                api_request_id = record.requestId.id,
                json_path = record.jsonPath.value,
            )
        }
    }
}
