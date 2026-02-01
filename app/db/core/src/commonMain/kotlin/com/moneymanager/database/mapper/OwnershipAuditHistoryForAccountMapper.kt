@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectOwnershipAuditHistoryForAccount
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.SourceType
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object OwnershipAuditHistoryForAccountMapper :
    ObjectMappie<SelectOwnershipAuditHistoryForAccount, PersonAccountOwnershipAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectOwnershipAuditHistoryForAccount): PersonAccountOwnershipAuditEntry =
        mapping {
            PersonAccountOwnershipAuditEntry::personFullName fromValue
                buildPersonFullName(
                    from.person_first_name,
                    from.person_middle_name,
                    from.person_last_name,
                )
            PersonAccountOwnershipAuditEntry::source fromValue from.toEntitySource()
        }
}

private fun buildPersonFullName(
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

private fun SelectOwnershipAuditHistoryForAccount.toEntitySource(): EntitySource? {
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
        entityType = EntityType.PERSON_ACCOUNT_OWNERSHIP,
        entityId = person_account_ownership_id,
        revisionId = revision_id,
        sourceType = SourceType.fromName(sourceTypeName),
        deviceId = deviceId,
        deviceInfo = deviceInfo,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )
}
