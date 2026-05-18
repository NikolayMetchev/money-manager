@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForCurrency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import tech.mappie.api.ObjectMappie

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
    val deviceInfo =
        auditDeviceInfo(
            platformName = source_platform_name,
            machineName = source_machine_name,
            osName = source_os_name,
            deviceMake = source_device_make,
            deviceModel = source_device_model,
        )

    return auditEntitySource(
        sourceId = source_id,
        sourceTypeName = source_type_name,
        deviceId = source_device_id,
        createdAt = source_created_at,
        entityType = EntityType.CURRENCY,
        entityId = currency_id,
        revisionId = revision_id,
        deviceInfo = deviceInfo,
    )
}
