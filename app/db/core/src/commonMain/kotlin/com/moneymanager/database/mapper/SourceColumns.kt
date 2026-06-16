@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The flat source columns selected by every audit/source query, split into the per-entity head
 * (which row this source belongs to and when) and the common [SourceDetailColumns] tail (device
 * metadata plus the per-source-type import detail columns). Splitting out the tail — which every
 * query selects under the same generated names — keeps each mapper's construction down to its own
 * entity head, instead of repeating the ~14 identical detail columns in every mapper.
 *
 * Each mapper maps its query row's columns onto these fields; [buildSourceRecord] then reconstructs
 * the single read-side [SourceRecord] (with its [Source] sealed value) from them. This is the one
 * place entity- and transfer-source rows are turned into the unified read model.
 */
internal data class SourceColumns(
    val sourceId: Long?,
    val sourceTypeName: String?,
    val deviceId: Long?,
    val createdAt: Long?,
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val detail: SourceDetailColumns,
)

/**
 * The device-metadata and per-source-type import detail columns shared by every audit/source query.
 * Sources without per-type import detail rows simply pass nulls for the csv/qif/api fields.
 */
internal data class SourceDetailColumns(
    val platformName: String?,
    val osName: String?,
    val machineName: String?,
    val deviceMake: String?,
    val deviceModel: String?,
    val csvImportId: String?,
    val csvRowIndex: Long?,
    val csvFileName: String?,
    val qifImportId: String?,
    val qifRecordIndex: Long?,
    val qifFileName: String?,
    val apiSessionId: Long?,
    val apiRequestId: Long?,
    val apiJsonPath: String?,
)

/**
 * Reconstructs the unified [SourceRecord] from the flat source columns, or null when no source row
 * exists for this revision. The [Source] sealed value mirrors [Source.toSourceType]'s inverse, with
 * import ids/indexes/session/request/jsonPath read from the detail columns (when present), while
 * [SourceRecord] carries the row id, device info, join-derived import file name and timestamp.
 */
internal fun buildSourceRecord(columns: SourceColumns): SourceRecord? {
    val sourceId = columns.sourceId ?: return null
    val sourceTypeName = columns.sourceTypeName ?: return null
    val deviceId = columns.deviceId ?: return null
    val createdAt = columns.createdAt ?: return null

    val detail = columns.detail
    val (source, fileName) = detail.reconstructSource(SourceType.fromName(sourceTypeName))

    return SourceRecord(
        id = sourceId,
        entityType = columns.entityType,
        entityId = columns.entityId,
        revisionId = columns.revisionId,
        source = source,
        deviceId = deviceId,
        deviceInfo =
            auditDeviceInfo(
                platformName = detail.platformName,
                machineName = detail.machineName,
                osName = detail.osName,
                deviceMake = detail.deviceMake,
                deviceModel = detail.deviceModel,
            ),
        fileName = fileName,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )
}

/**
 * Reconstructs the [Source] for [sourceType] from this row's detail columns, paired with the
 * join-derived import file name (CSV/QIF only; null otherwise). Mirrors [Source.toSourceType].
 */
private fun SourceDetailColumns.reconstructSource(sourceType: SourceType): Pair<Source, String?> =
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
 * Reuses [DeviceRepositoryImpl.createDeviceInfo]'s logic but tolerates unknown/absent platform by
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
