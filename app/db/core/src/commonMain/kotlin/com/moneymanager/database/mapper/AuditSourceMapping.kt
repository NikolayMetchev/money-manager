@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.ApiSourceDetails
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceType
import kotlin.time.Instant

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

internal fun auditEntitySource(
    sourceId: Long?,
    sourceTypeId: Long?,
    sourceTypeName: String?,
    deviceId: Long?,
    createdAt: Long?,
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    deviceInfo: DeviceInfo?,
    apiSource: ApiSourceDetails? = null,
): EntitySource? {
    val resolvedSourceId = sourceId ?: return null
    sourceTypeId ?: return null
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
    )
}
