@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.currencies

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.audit.resolveUpdateChange
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun CurrencyAuditScreen(
    currencyId: CurrencyId,
    auditRepository: AuditReadRepository,
    currencyRepository: CurrencyReadRepository,
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
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = listOf(code, name, scaleFactor).any { it is FieldChange.Changed }
}

private fun computeCurrencyAuditDiffs(
    entries: List<CurrencyAuditEntry>,
    currentCurrency: Currency?,
): List<CurrencyAuditDiff> =
    entries.mapIndexed { index, entry ->
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
                val previousEntry = entries.getOrNull(index - 1)

                CurrencyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    code =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentCurrency,
                            previousEntry = previousEntry,
                            entryValue = entry.code,
                            currentValue = { it.code },
                            previousValue = { it.code },
                        ),
                    name =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentCurrency,
                            previousEntry = previousEntry,
                            entryValue = entry.name,
                            currentValue = { it.name },
                            previousValue = { it.name },
                        ),
                    scaleFactor =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentCurrency,
                            previousEntry = previousEntry,
                            entryValue = entry.scaleFactor,
                            currentValue = { it.scaleFactor },
                            previousValue = { it.scaleFactor },
                        ),
                    source = entry.source,
                )
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
