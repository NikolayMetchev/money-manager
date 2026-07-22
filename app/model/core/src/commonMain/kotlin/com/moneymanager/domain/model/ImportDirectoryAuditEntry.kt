@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * A single revision of an [com.moneymanager.domain.model.importdirectory.ImportDirectory] as captured
 * in the audit trail, with its provenance [source]. Mirrors [CsvImportStrategyAuditEntry].
 */
data class ImportDirectoryAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val directoryId: ImportDirectoryId,
    val revisionId: Long,
    val name: String,
    val providerType: String,
    val folderRef: String,
    val displayPath: String?,
    val providerConfig: String?,
    val deviceId: DeviceId?,
    val topLevel: Boolean,
    val parentId: ImportDirectoryId?,
    val excluded: Boolean,
    val accountId: AccountId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: SourceRecord? = null,
)
