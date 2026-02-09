@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant

@Composable
fun CurrencyAuditScreen(
    currencyId: CurrencyId,
    auditRepository: AuditRepository,
    currencyRepository: CurrencyRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Currency Audit: $currencyId",
        entityTypeName = "currency",
        loadKey = currencyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCurrency(currencyId)
            val currentCurrency = currencyRepository.getCurrencyById(currencyId).first()
            val diffs = computeCurrencyAuditDiffs(entries, currentCurrency)
            AuditScreenData(
                title = "Currency Audit: ${currentCurrency?.code ?: currencyId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> CurrencyAuditDiffCard(diff) },
    )
}

private data class CurrencyAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val code: FieldChange<String>,
    val name: FieldChange<String>,
    val scaleFactor: FieldChange<Long>,
    val source: EntitySource?,
) {
    val hasChanges: Boolean
        get() = listOf(code, name, scaleFactor).any { it is FieldChange.Changed }
}

private fun computeCurrencyAuditDiffs(
    entries: List<CurrencyAuditEntry>,
    currentCurrency: Currency?,
): List<CurrencyAuditDiff> {
    return entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                CurrencyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    code = FieldChange.Created(entry.code),
                    name = FieldChange.Created(entry.name),
                    scaleFactor = FieldChange.Created(entry.scaleFactor),
                    source = entry.source,
                )
            AuditType.DELETE ->
                CurrencyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    code = FieldChange.Deleted(entry.code),
                    name = FieldChange.Deleted(entry.name),
                    scaleFactor = FieldChange.Deleted(entry.scaleFactor),
                    source = entry.source,
                )
            AuditType.UPDATE -> {
                val newCode =
                    if (index == 0 && currentCurrency != null) {
                        currentCurrency.code
                    } else if (index > 0) {
                        entries[index - 1].code
                    } else {
                        entry.code
                    }
                val newName =
                    if (index == 0 && currentCurrency != null) {
                        currentCurrency.name
                    } else if (index > 0) {
                        entries[index - 1].name
                    } else {
                        entry.name
                    }
                val newScaleFactor =
                    if (index == 0 && currentCurrency != null) {
                        currentCurrency.scaleFactor
                    } else if (index > 0) {
                        entries[index - 1].scaleFactor
                    } else {
                        entry.scaleFactor
                    }

                CurrencyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    code =
                        if (entry.code != newCode) {
                            FieldChange.Changed(entry.code, newCode)
                        } else {
                            FieldChange.Unchanged(entry.code)
                        },
                    name =
                        if (entry.name != newName) {
                            FieldChange.Changed(entry.name, newName)
                        } else {
                            FieldChange.Unchanged(entry.name)
                        },
                    scaleFactor =
                        if (entry.scaleFactor != newScaleFactor) {
                            FieldChange.Changed(entry.scaleFactor, newScaleFactor)
                        } else {
                            FieldChange.Unchanged(entry.scaleFactor)
                        },
                    source = entry.source,
                )
            }
        }
    }
}

@Composable
private fun CurrencyAuditDiffCard(diff: CurrencyAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> {
                Text(
                    text = "Created with:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FieldValueRow("Code", diff.code.value())
                FieldValueRow("Name", diff.name.value())
                FieldValueRow("Scale Factor", diff.scaleFactor.value().toString())
                SourceInfoSection(diff.source)
            }
            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    Text(
                        text = "No visible changes recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Changed:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val codeChange = diff.code
                    if (codeChange is FieldChange.Changed) {
                        FieldChangeRow("Code", codeChange.oldValue, codeChange.newValue)
                    }
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue)
                    }
                    val scaleFactorChange = diff.scaleFactor
                    if (scaleFactorChange is FieldChange.Changed) {
                        FieldChangeRow(
                            "Scale Factor",
                            scaleFactorChange.oldValue.toString(),
                            scaleFactorChange.newValue.toString(),
                        )
                    }
                }
                SourceInfoSection(diff.source)
            }
            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                Text(
                    text = "Deleted (final values):",
                    style = MaterialTheme.typography.labelMedium,
                    color = errorColor.copy(alpha = 0.8f),
                )
                FieldValueRow("Code", diff.code.value(), errorColor)
                FieldValueRow("Name", diff.name.value(), errorColor)
                FieldValueRow("Scale Factor", diff.scaleFactor.value().toString(), errorColor)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}
