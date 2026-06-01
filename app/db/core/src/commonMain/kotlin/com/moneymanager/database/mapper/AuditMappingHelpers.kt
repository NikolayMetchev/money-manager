@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

internal fun buildPersonFullName(
    firstName: String?,
    middleName: String?,
    lastName: String?,
): String? =
    listOf(firstName, middleName, lastName)
        .filterNot { it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { null }

internal fun buildPersonAccountOwnershipEntitySource(
    sourceId: Long?,
    sourceTypeId: Long?,
    sourceTypeName: String?,
    sourceDeviceId: Long?,
    sourceCreatedAt: Long?,
    sourcePlatformName: String?,
    sourceMachineName: String?,
    sourceOsName: String?,
    sourceDeviceMake: String?,
    sourceDeviceModel: String?,
    entityId: Long,
    revisionId: Long,
    sourceApiSessionId: Long?,
    sourceApiRequestId: Long?,
    sourceApiJsonPath: String?,
): EntitySource? {
    val resolvedSourceId = sourceId ?: return null
    sourceTypeId ?: return null
    val resolvedSourceTypeName = sourceTypeName ?: return null
    val resolvedDeviceId = sourceDeviceId ?: return null
    val resolvedCreatedAt = sourceCreatedAt ?: return null
    return buildEntitySource(
        sourceId = resolvedSourceId,
        sourceTypeName = resolvedSourceTypeName,
        deviceId = resolvedDeviceId,
        sourcePlatformName = sourcePlatformName,
        sourceMachineName = sourceMachineName,
        sourceOsName = sourceOsName,
        sourceDeviceMake = sourceDeviceMake,
        sourceDeviceModel = sourceDeviceModel,
        createdAt = resolvedCreatedAt,
        entityType = EntityType.PERSON_ACCOUNT_OWNERSHIP,
        entityId = entityId,
        revisionId = revisionId,
        sourceApiSessionId = sourceApiSessionId,
        sourceApiRequestId = sourceApiRequestId,
        sourceApiJsonPath = sourceApiJsonPath,
    )
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
