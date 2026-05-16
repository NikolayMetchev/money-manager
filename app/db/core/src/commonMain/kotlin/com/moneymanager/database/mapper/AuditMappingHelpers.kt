@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceType
import kotlin.time.Instant

internal fun buildPersonFullName(
    firstName: String?,
    middleName: String?,
    lastName: String?,
): String? {
    if (firstName == null) return null
    return buildString {
        append(firstName)
        if (!middleName.isNullOrBlank()) {
            append(" ")
            append(middleName)
        }
        if (!lastName.isNullOrBlank()) {
            append(" ")
            append(lastName)
        }
    }
}

internal fun buildEntitySource(
    sourceId: Long,
    sourceTypeName: String,
    deviceId: Long,
    sourcePlatformName: String?,
    sourceMachineName: String?,
    sourceOsName: String?,
    sourceDeviceMake: String?,
    sourceDeviceModel: String?,
    createdAt: Long,
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    sourceApiSessionId: Long? = null,
    sourceApiRequestId: Long? = null,
    sourceApiJsonPath: String? = null,
): EntitySource =
    EntitySource(
        id = sourceId,
        entityType = entityType,
        entityId = entityId,
        revisionId = revisionId,
        sourceType = SourceType.fromName(sourceTypeName),
        deviceId = deviceId,
        deviceInfo =
            when (sourcePlatformName) {
                "JVM" ->
                    DeviceInfo.Jvm(
                        machineName = sourceMachineName ?: "Unknown",
                        osName = sourceOsName ?: "Unknown",
                    )
                "Android" ->
                    DeviceInfo.Android(
                        deviceMake = sourceDeviceMake ?: "Unknown",
                        deviceModel = sourceDeviceModel ?: "Unknown",
                    )
                else -> null
            },
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        apiSource =
            if (sourceApiSessionId != null && sourceApiRequestId != null && sourceApiJsonPath != null) {
                ApiSourceDetails(
                    sessionId = ApiSessionId(sourceApiSessionId),
                    requestId = ApiRequestId(sourceApiRequestId),
                    jsonPath = JsonPath(sourceApiJsonPath),
                )
            } else {
                null
            },
    )
