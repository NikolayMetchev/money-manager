@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.screens.changedOrUnchanged
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun ApiImportStrategyAuditScreen(
    strategyId: ApiImportStrategyId,
    auditRepository: AuditRepository,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "API Strategy Audit: $strategyId",
        entityTypeName = "API import strategy",
        loadKey = strategyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForApiImportStrategy(strategyId)
            val currentStrategy = apiImportStrategyRepository.getStrategyById(strategyId).first()
            val diffs = computeApiImportStrategyAuditDiffs(entries, currentStrategy?.name)
            AuditScreenData(
                title = "API Strategy Audit: ${currentStrategy?.name ?: strategyId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> ApiImportStrategyAuditDiffCard(diff) },
    )
}

private data class ApiImportStrategyAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val configChanged: Boolean,
) {
    val hasChanges: Boolean
        get() = name is FieldChange.Changed || configChanged
}

private fun computeApiImportStrategyAuditDiffs(
    entries: List<ApiImportStrategyAuditEntry>,
    currentName: String?,
): List<ApiImportStrategyAuditDiff> =
    entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    configChanged = false,
                )
            AuditType.DELETE ->
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    configChanged = false,
                )
            AuditType.UPDATE -> {
                val previousEntry = entries.getOrNull(index - 1)
                val newName =
                    when {
                        index == 0 && currentName != null -> currentName
                        index > 0 && previousEntry != null -> previousEntry.name
                        else -> entry.name
                    }
                val newConfigJson =
                    when {
                        index > 0 && previousEntry != null -> previousEntry.configJson
                        else -> entry.configJson
                    }
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = changedOrUnchanged(entry.name, newName),
                    configChanged = entry.configJson != newConfigJson,
                )
            }
        }
    }

@Composable
private fun ApiImportStrategyAuditDiffCard(diff: ApiImportStrategyAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> {
                AuditSectionLabel("Created with:")
                FieldValueRow("Name", diff.name.value())
            }
            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    NoVisibleChangesText()
                } else {
                    AuditSectionLabel("Changed:")
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue)
                    }
                    if (diff.configChanged) {
                        FieldValueRow(
                            "Configuration",
                            "modified",
                            valueColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                AuditSectionLabel("Deleted (final values):")
                FieldValueRow("Name", diff.name.value(), errorColor)
            }
        }
    }
}
