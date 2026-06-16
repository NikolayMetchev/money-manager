@file:OptIn(ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.CsvSourceDetails
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.csv.CsvImportId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun auditDeviceInfo(
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
        "Android" ->
            DeviceInfo.Android(
                deviceMake = deviceMake ?: "Unknown",
                deviceModel = deviceModel ?: "Unknown",
            )
        else -> null
    }

internal fun auditApiSource(
    sessionId: Long?,
    requestId: Long?,
    jsonPath: String?,
): ApiSourceDetails? =
    if (sessionId != null && requestId != null && jsonPath != null) {
        ApiSourceDetails(
            sessionId = ApiSessionId(sessionId),
            requestId = ApiRequestId(requestId),
            jsonPath = JsonPath(jsonPath),
        )
    } else {
        null
    }

internal fun auditCsvSource(
    importId: String?,
    rowIndex: Long?,
    fileName: String?,
): CsvSourceDetails? =
    if (importId != null && rowIndex != null) {
        CsvSourceDetails(
            importId = CsvImportId(Uuid.parse(importId)),
            rowIndex = rowIndex,
            fileName = fileName,
        )
    } else {
        null
    }

internal fun auditEntitySource(
    sourceId: Long?,
    sourceTypeName: String?,
    deviceId: Long?,
    createdAt: Long?,
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    deviceInfo: DeviceInfo?,
    apiSource: ApiSourceDetails? = null,
    csvSource: CsvSourceDetails? = null,
): EntitySource? {
    val resolvedSourceId = sourceId ?: return null
    val resolvedSourceTypeName = sourceTypeName ?: return null
    val resolvedDeviceId = deviceId ?: return null
    val resolvedCreatedAt = createdAt ?: return null

    return EntitySource(
        id = resolvedSourceId,
        entityType = entityType,
        entityId = entityId,
        revisionId = revisionId,
        sourceType = SourceType.fromName(resolvedSourceTypeName),
        deviceId = resolvedDeviceId,
        deviceInfo = deviceInfo,
        createdAt = Instant.fromEpochMilliseconds(resolvedCreatedAt),
        apiSource = apiSource,
        csvSource = csvSource,
    )
}
