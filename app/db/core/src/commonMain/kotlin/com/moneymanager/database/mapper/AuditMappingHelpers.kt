@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType

internal fun buildPersonFullName(
    firstName: String?,
    middleName: String?,
    lastName: String?,
): String? =
    listOf(firstName, middleName, lastName)
        .filterNot { it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { null }

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
    auditEntitySource(
        sourceId = sourceId,
        sourceTypeName = sourceTypeName,
        deviceId = deviceId,
        createdAt = createdAt,
        entityType = entityType,
        entityId = entityId,
        revisionId = revisionId,
        deviceInfo =
            auditDeviceInfo(
                platformName = sourcePlatformName,
                machineName = sourceMachineName,
                osName = sourceOsName,
                deviceMake = sourceDeviceMake,
                deviceModel = sourceDeviceModel,
            ),
        apiSource =
            auditApiSource(
                sessionId = sourceApiSessionId,
                requestId = sourceApiRequestId,
                jsonPath = sourceApiJsonPath,
            ),
    ) ?: error("Audit source fields are required")
