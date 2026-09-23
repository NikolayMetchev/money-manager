package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.SourceRecord
import kotlin.time.Instant

/**
 * A single revision of a [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy] as captured in
 * the audit trail, with its provenance [source]. Mirrors `ApiImportStrategyAuditEntry`.
 */
data class CsvImportStrategyAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val strategyId: CsvImportStrategyId,
    val revisionId: Long,
    val name: String,
    val config: CsvStrategyConfig<FieldMapping>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: SourceRecord? = null,
)
