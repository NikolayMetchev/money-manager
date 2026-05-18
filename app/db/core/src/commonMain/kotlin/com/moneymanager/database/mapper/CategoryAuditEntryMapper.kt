@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForCategory
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import tech.mappie.api.ObjectMappie

object CategoryAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCategory, CategoryAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCategory): CategoryAuditEntry =
        mapping {
            CategoryAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun SelectAuditHistoryForCategory.toEntitySource(): EntitySource? {
    val sourceId = source_id ?: return null
    source_type_id ?: return null
    val sourceTypeName = source_type_name ?: return null
    val deviceId = source_device_id ?: return null
    val createdAt = source_created_at ?: return null

    return buildEntitySource(
        sourceId = sourceId,
        sourceTypeName = sourceTypeName,
        deviceId = deviceId,
        sourcePlatformName = source_platform_name,
        sourceMachineName = source_machine_name,
        sourceOsName = source_os_name,
        sourceDeviceMake = source_device_make,
        sourceDeviceModel = source_device_model,
        createdAt = createdAt,
        entityType = EntityType.CATEGORY,
        entityId = category_id,
        revisionId = revision_id,
    )
}
