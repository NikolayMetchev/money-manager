@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.time.Instant

/**
 * A single revision of a [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy] as captured in
 * the audit trail, with its provenance [source]. Mirrors [ApiImportStrategyAuditEntry].
 */
data class CsvImportStrategyAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val strategyId: CsvImportStrategyId,
    val revisionId: Long,
    val name: String,
    val identificationColumns: Set<String>,
    val fieldMappings: Map<TransferField, FieldMapping>,
    val attributeMappings: List<AttributeColumnMapping>,
    val rowPreprocessingRules: List<RowPreprocessingRule>,
    val companionTransactionRules: List<CompanionTransactionRule>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: SourceRecord? = null,
)
