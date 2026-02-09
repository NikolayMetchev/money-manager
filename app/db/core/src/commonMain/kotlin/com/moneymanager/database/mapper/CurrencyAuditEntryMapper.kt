@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForCurrency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceType
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object CurrencyAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCurrency, CurrencyAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCurrency): CurrencyAuditEntry =
        mapping {
            CurrencyAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun SelectAuditHistoryForCurrency.toEntitySource(): EntitySource? {
    val sourceId = source_id ?: return null
    source_type_id ?: return null
    val sourceTypeName = source_type_name ?: return null
    val deviceId = source_device_id ?: return null
    val createdAt = source_created_at ?: return null

    val deviceInfo =
        when (source_platform_name) {
            "JVM" ->
                DeviceInfo.Jvm(
                    machineName = source_machine_name ?: "Unknown",
                    osName = source_os_name ?: "Unknown",
                )
            "Android" ->
                DeviceInfo.Android(
                    deviceMake = source_device_make ?: "Unknown",
                    deviceModel = source_device_model ?: "Unknown",
                )
            else -> null
        }

    return EntitySource(
        id = sourceId,
        entityType = EntityType.CURRENCY,
        entityId = currency_id,
        revisionId = revision_id,
        sourceType = SourceType.fromName(sourceTypeName),
        deviceId = deviceId,
        deviceInfo = deviceInfo,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )
}
