@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForImportDirectory
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.ImportDirectoryAuditEntry
import com.moneymanager.domain.model.ImportDirectoryId
import kotlin.time.Instant
import kotlin.uuid.Uuid

object ImportDirectoryAuditEntryMapper {
    fun map(from: SelectAuditHistoryForImportDirectory): ImportDirectoryAuditEntry {
        // import_directory_source has no per-type detail rows (directories are only manually/system
        // created), so the import-detail columns are absent here.
        val source =
            buildSourceRecord(
                SourceColumns(
                    sourceId = from.source_id,
                    sourceTypeName = from.source_type_name,
                    deviceId = from.source_device_id,
                    createdAt = from.source_created_at,
                    entityType = EntityType.IMPORT_DIRECTORY,
                    entityId = 0,
                    revisionId = from.revision_id,
                    detail =
                        SourceDetailColumns(
                            platformName = from.source_platform_name,
                            osName = from.source_os_name,
                            machineName = from.source_machine_name,
                            deviceMake = from.source_device_make,
                            deviceModel = from.source_device_model,
                            csvImportId = null,
                            csvRowIndex = null,
                            csvFileName = null,
                            qifImportId = null,
                            qifRecordIndex = null,
                            qifFileName = null,
                            apiSessionId = null,
                            apiRequestId = null,
                            apiJsonPath = null,
                        ),
                ),
            )
        return ImportDirectoryAuditEntry(
            id = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            directoryId = ImportDirectoryId(Uuid.parse(from.import_directory_id)),
            revisionId = from.revision_id,
            name = from.name,
            providerType = from.provider_type,
            folderRef = from.folder_ref,
            displayPath = from.folder_display_path,
            providerConfig = from.provider_config,
            deviceId = from.device_id?.let(::DeviceId),
            topLevel = from.top_level != 0L,
            parentId = from.parent_id?.let { ImportDirectoryId(Uuid.parse(it)) },
            excluded = from.excluded != 0L,
            accountId = from.account_id?.let(::AccountId),
            createdAt = Instant.fromEpochMilliseconds(from.created_at),
            updatedAt = Instant.fromEpochMilliseconds(from.updated_at),
            source = source,
        )
    }
}
