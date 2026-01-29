@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectOwnershipAuditHistoryForAccountWithSource
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.SourceType
import kotlin.time.Instant

object OwnershipAuditHistoryForAccountWithSourceMapper {
    fun map(from: SelectOwnershipAuditHistoryForAccountWithSource): PersonAccountOwnershipAuditEntry {
        val source =
            if (from.source_id != null) {
                EntitySource(
                    id = from.source_id,
                    entityType = EntityType.PERSON_ACCOUNT_OWNERSHIP,
                    entityId = from.ownership_id,
                    revisionId = from.revision_id,
                    sourceType = mapSourceType(from.source_type_name),
                    deviceId = from.source_device_id!!,
                    deviceInfo = buildDeviceInfo(from),
                    createdAt = Instant.fromEpochMilliseconds(from.source_created_at!!),
                )
            } else {
                null
            }

        return PersonAccountOwnershipAuditEntry(
            auditId = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            auditType = mapAuditType(from.audit_type),
            ownershipId = from.ownership_id,
            revisionId = from.revision_id,
            personId = toPersonId(from.person_id),
            accountId = toAccountId(from.account_id),
            personFullName =
                buildPersonFullName(
                    from.person_first_name,
                    from.person_middle_name,
                    from.person_last_name,
                ),
            accountName = from.account_name,
            source = source,
        )
    }

    private fun mapAuditType(name: String): com.moneymanager.domain.model.AuditType =
        when (name) {
            "INSERT" -> com.moneymanager.domain.model.AuditType.INSERT
            "UPDATE" -> com.moneymanager.domain.model.AuditType.UPDATE
            "DELETE" -> com.moneymanager.domain.model.AuditType.DELETE
            else -> error("Unknown audit type: $name")
        }

    private fun mapSourceType(name: String?): SourceType =
        when (name) {
            "MANUAL" -> SourceType.MANUAL
            "CSV_IMPORT" -> SourceType.CSV_IMPORT
            "SAMPLE_GENERATOR" -> SourceType.SAMPLE_GENERATOR
            "SYSTEM" -> SourceType.SYSTEM
            else -> SourceType.MANUAL
        }

    private fun buildDeviceInfo(from: SelectOwnershipAuditHistoryForAccountWithSource): DeviceInfo? {
        val platformName = from.source_platform_name ?: return null

        return when (platformName) {
            "JVM" ->
                DeviceInfo.Jvm(
                    osName = from.source_os_name ?: "Unknown",
                    machineName = from.source_machine_name ?: "Unknown",
                )
            "Android" ->
                DeviceInfo.Android(
                    deviceMake = from.source_device_make ?: "Unknown",
                    deviceModel = from.source_device_model ?: "Unknown",
                )
            else -> null
        }
    }

    private fun toPersonId(id: Long) = com.moneymanager.domain.model.PersonId(id)

    private fun toAccountId(id: Long) = com.moneymanager.domain.model.AccountId(id)

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
}
