@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.audit.changedOrUnchanged
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun CsvImportStrategyAuditScreen(
    strategyId: CsvImportStrategyId,
    auditRepository: AuditReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "CSV Strategy Audit: $strategyId",
        entityTypeName = "CSV import strategy",
        loadKey = strategyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCsvImportStrategy(strategyId)
            val current = csvImportStrategyRepository.getStrategyById(strategyId).first()
            val currentConfig =
                current?.let {
                    flattenStrategy(
                        identificationColumns = it.identificationColumns,
                        fieldMappings = it.fieldMappings,
                        attributeMappings = it.attributeMappings,
                        rowRules = it.rowPreprocessingRules,
                        companionRules = it.companionTransactionRules,
                    )
                }
            val diffs =
                computeCsvImportStrategyAuditDiffs(
                    entries = entries,
                    currentName = current?.name,
                    currentConfig = currentConfig,
                )
            AuditScreenData(
                title = "CSV Strategy Audit: ${current?.name ?: strategyId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> CsvImportStrategyAuditDiffCard(diff) },
    )
}

// ─── Diff model ──────────────────────────────────────────────────────────────

private data class CsvImportStrategyAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val configChanges: List<Pair<String, FieldChange<String>>>,
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = name is FieldChange.Changed || configChanges.isNotEmpty()
}

// ─── Diff computation ─────────────────────────────────────────────────────────

private fun computeCsvImportStrategyAuditDiffs(
    entries: List<CsvImportStrategyAuditEntry>,
    currentName: String?,
    currentConfig: Map<String, String>?,
): List<CsvImportStrategyAuditDiff> =
    entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                CsvImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.DELETE ->
                CsvImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.UPDATE -> {
                val previousEntry = entries.getOrNull(index - 1)
                val newName =
                    when {
                        index == 0 && currentName != null -> currentName
                        index > 0 && previousEntry != null -> previousEntry.name
                        else -> entry.name
                    }
                val newConfig =
                    when {
                        index == 0 -> currentConfig
                        previousEntry != null -> previousEntry.flatten()
                        else -> null
                    }
                CsvImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = changedOrUnchanged(entry.name, newName),
                    configChanges = if (newConfig != null) diffConfigs(entry.flatten(), newConfig) else emptyList(),
                    source = entry.source,
                )
            }
        }
    }

private fun CsvImportStrategyAuditEntry.flatten(): Map<String, String> =
    flattenStrategy(
        identificationColumns = identificationColumns,
        fieldMappings = fieldMappings,
        attributeMappings = attributeMappings,
        rowRules = rowPreprocessingRules,
        companionRules = companionTransactionRules,
    )

/**
 * Returns only the fields that differ between [oldConfig] (state before this change) and [newConfig]
 * (state after), as label→(old,new) changes.
 */
private fun diffConfigs(
    oldConfig: Map<String, String>,
    newConfig: Map<String, String>,
): List<Pair<String, FieldChange<String>>> =
    oldConfig.keys
        .union(newConfig.keys)
        .sorted()
        .mapNotNull { key ->
            val o = oldConfig[key] ?: ""
            val n = newConfig[key] ?: ""
            if (o != n) key to FieldChange.Changed(o, n) else null
        }

private fun flattenStrategy(
    identificationColumns: Set<String>,
    fieldMappings: Map<TransferField, FieldMapping>,
    attributeMappings: List<AttributeColumnMapping>,
    rowRules: List<RowPreprocessingRule>,
    companionRules: List<CompanionTransactionRule>,
): Map<String, String> =
    buildMap {
        put("Identification columns", identificationColumns.sorted().joinToString(", "))
        fieldMappings.entries
            .sortedBy { it.key.name }
            .forEach { (field, mapping) -> put("Field: ${field.name}", mapping.toString()) }
        if (attributeMappings.isNotEmpty()) {
            put("Attribute mappings", attributeMappings.joinToString("; ") { it.toString() })
        }
        if (rowRules.isNotEmpty()) {
            put("Row rules", rowRules.joinToString("; ") { it.toString() })
        }
        if (companionRules.isNotEmpty()) {
            put("Companion rules", companionRules.joinToString("; ") { it.toString() })
        }
    }

// ─── Diff card ────────────────────────────────────────────────────────────────

@Composable
private fun CsvImportStrategyAuditDiffCard(diff: CsvImportStrategyAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> {
                AuditSectionLabel("Created with:")
                FieldValueRow("Name", diff.name.value())
                SourceInfoSection(diff.source)
            }

            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    NoVisibleChangesText()
                } else {
                    AuditSectionLabel("Changed:")
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue, labelWidth = 200.dp)
                    }
                    diff.configChanges.forEach { (label, change) ->
                        if (change is FieldChange.Changed) {
                            FieldChangeRow(label, change.oldValue, change.newValue, labelWidth = 200.dp)
                        }
                    }
                }
                SourceInfoSection(diff.source)
            }

            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                AuditSectionLabel("Deleted (final values):")
                FieldValueRow("Name", diff.name.value(), errorColor)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}
