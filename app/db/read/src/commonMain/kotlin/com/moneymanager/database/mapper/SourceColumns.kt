package com.moneymanager.database.mapper

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.SourceType
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The flat source columns a source-carrying query selects: which row this source belongs to and
 * when, the device metadata, and the per-source-type import detail. A source with no per-type
 * detail row (the strategy and import-directory tables have none) simply leaves the csv/qif/api
 * fields at their null defaults.
 *
 * Four call sites build one: [toSourceRecord] for everything in the unified `entity_source` store,
 * and the three mappers whose provenance lives in a dedicated `*_source` table. Audit queries used
 * to select these columns once per entity type, which made this a shape every audit mapper
 * repeated; they no longer do — see `selectEntitySources` in EntitySourceSelect.sq.
 *
 * [buildSourceRecord] turns these columns into the single read-side [SourceRecord] (with its
 * [Source] sealed value). This is the one place source rows become the unified read model.
 */
data class SourceColumns(
    val sourceId: Long?,
    val sourceTypeName: String?,
    val deviceId: Long?,
    val createdAt: Long?,
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val platformName: String?,
    val osName: String?,
    val machineName: String?,
    val deviceMake: String?,
    val deviceModel: String?,
    val csvImportId: String? = null,
    val csvRowIndex: Long? = null,
    val csvFileName: String? = null,
    val qifImportId: String? = null,
    val qifRecordIndex: Long? = null,
    val qifFileName: String? = null,
    val apiSessionId: Long? = null,
    val apiRequestId: Long? = null,
    val apiJsonPath: String? = null,
)

/**
 * Reconstructs the unified [SourceRecord] from the flat source columns, or null when no source row
 * exists for this revision. The [Source] sealed value mirrors `Source.toSourceType`'s inverse, with
 * import ids/indexes/session/request/jsonPath read from the detail columns (when present), while
 * [SourceRecord] carries the row id, device info, join-derived import file name and timestamp.
 */
fun buildSourceRecord(columns: SourceColumns): SourceRecord? {
    val sourceId = columns.sourceId ?: return null
    val sourceTypeName = columns.sourceTypeName ?: return null
    val deviceId = columns.deviceId ?: return null
    val createdAt = columns.createdAt ?: return null

    val (source, fileName) = columns.reconstructSource(SourceType.fromName(sourceTypeName))

    return SourceRecord(
        id = sourceId,
        entityType = columns.entityType,
        entityId = columns.entityId,
        revisionId = columns.revisionId,
        source = source,
        deviceId = deviceId,
        deviceInfo =
            auditDeviceInfo(
                platformName = columns.platformName,
                machineName = columns.machineName,
                osName = columns.osName,
                deviceMake = columns.deviceMake,
                deviceModel = columns.deviceModel,
            ),
        fileName = fileName,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )
}

/**
 * Reconstructs the [Source] for [sourceType] from this row's detail columns, paired with the
 * join-derived import file name (CSV/QIF only; null otherwise). Mirrors `Source.toSourceType`.
 */
private fun SourceColumns.reconstructSource(sourceType: SourceType): Pair<Source, String?> =
    when (sourceType) {
        SourceType.MANUAL -> Source.Manual to null
        SourceType.SAMPLE_GENERATOR -> Source.SampleGenerator to null
        SourceType.SYSTEM -> Source.System to null
        SourceType.MERGE -> Source.Merge to null
        SourceType.MERGE_UNDO -> Source.Unmerge to null
        SourceType.CSV_IMPORT ->
            Source.Csv(
                importId = CsvImportId(Uuid.parse(checkNotNull(csvImportId) { "CSV source row missing csv_import_id" })),
                rowIndex = csvRowIndex,
            ) to csvFileName
        SourceType.QIF_IMPORT ->
            Source.Qif(
                importId = QifImportId(Uuid.parse(checkNotNull(qifImportId) { "QIF source row missing qif_import_id" })),
                recordIndex = qifRecordIndex,
            ) to qifFileName
        SourceType.API ->
            Source.Api(
                sessionId = ApiSessionId(checkNotNull(apiSessionId) { "API source row missing api_session_id" }),
                requestId = apiRequestId?.let { ApiRequestId(it) },
                jsonPath = apiJsonPath?.let { JsonPath(it) },
            ) to null
    }

/**
 * Reuses `DeviceRepositoryImpl.createDeviceInfo`'s logic but tolerates unknown/absent platform by
 * returning null (audit rows may lack device metadata), rather than throwing.
 */
private fun auditDeviceInfo(
    platformName: String?,
    machineName: String?,
    osName: String?,
    deviceMake: String?,
    deviceModel: String?,
): DeviceInfo? =
    when (platformName) {
        "JVM" ->
            DeviceInfo.Jvm(
                machineName = machineName ?: "Unknown",
                osName = osName ?: "Unknown",
            )
        "ANDROID", "Android" ->
            DeviceInfo.Android(
                deviceMake = deviceMake ?: "Unknown",
                deviceModel = deviceModel ?: "Unknown",
            )
        else -> null
    }
