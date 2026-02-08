@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun AccountAuditScreen(
    accountId: AccountId,
    auditRepository: AuditRepository,
    accountRepository: AccountRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Account Audit: $accountId",
        entityTypeName = "account",
        loadKey = accountId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForAccount(accountId)
            val ownershipEntries = auditRepository.getOwnershipAuditHistoryForAccount(accountId)
            val currentAccount = accountRepository.getAccountById(accountId).first()
            val diffs = computeAccountAuditDiffs(entries, ownershipEntries, currentAccount)
            AuditScreenData(
                title = "Account Audit: ${currentAccount?.name ?: accountId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> AccountAuditDiffCard(diff) },
    )
}

private data class AccountAuditDiff(
    val id: Long,
    val auditTimestamp: kotlin.time.Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val openingDate: FieldChange<kotlin.time.Instant>,
    val categoryName: FieldChange<String?>,
    val ownersAdded: List<String>,
    val ownersRemoved: List<String>,
    val source: EntitySource?,
) {
    val hasFieldChanges: Boolean
        get() = listOf(name, openingDate, categoryName).any { it is FieldChange.Changed }

    val hasOwnershipChanges: Boolean
        get() = ownersAdded.isNotEmpty() || ownersRemoved.isNotEmpty()

    val hasChanges: Boolean
        get() = hasFieldChanges || hasOwnershipChanges
}

private fun computeAccountAuditDiffs(
    entries: List<AccountAuditEntry>,
    ownershipEntries: List<PersonAccountOwnershipAuditEntry>,
    currentAccount: Account?,
): List<AccountAuditDiff> {
    val timestampWindowMs = 2000L

    data class OwnershipChanges(
        val ownersAdded: List<String>,
        val ownersRemoved: List<String>,
        val source: EntitySource?,
    )

    fun findOwnershipChangesForEntry(entry: AccountAuditEntry): OwnershipChanges {
        val entryTimestampMs = entry.auditTimestamp.toEpochMilliseconds()
        val matchingOwnershipChanges =
            ownershipEntries.filter { ownership ->
                val ownershipTimestampMs = ownership.auditTimestamp.toEpochMilliseconds()
                kotlin.math.abs(ownershipTimestampMs - entryTimestampMs) <= timestampWindowMs
            }

        val ownersAdded =
            matchingOwnershipChanges
                .filter { it.auditType == AuditType.INSERT }
                .map { it.personFullName ?: "Unknown (ID: ${it.personId.id})" }

        val ownersRemoved =
            matchingOwnershipChanges
                .filter { it.auditType == AuditType.DELETE }
                .map { it.personFullName ?: "Unknown (ID: ${it.personId.id})" }

        val ownershipSource = matchingOwnershipChanges.firstNotNullOfOrNull { it.source }

        return OwnershipChanges(ownersAdded, ownersRemoved, ownershipSource)
    }

    return entries.mapIndexed { index, entry ->
        val ownershipChanges = findOwnershipChangesForEntry(entry)
        val effectiveSource = entry.source ?: ownershipChanges.source

        when (entry.auditType) {
            AuditType.INSERT ->
                AccountAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    openingDate = FieldChange.Created(entry.openingDate),
                    categoryName = FieldChange.Created(entry.categoryName),
                    ownersAdded = ownershipChanges.ownersAdded,
                    ownersRemoved = ownershipChanges.ownersRemoved,
                    source = effectiveSource,
                )
            AuditType.DELETE ->
                AccountAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    openingDate = FieldChange.Deleted(entry.openingDate),
                    categoryName = FieldChange.Deleted(entry.categoryName),
                    ownersAdded = ownershipChanges.ownersAdded,
                    ownersRemoved = ownershipChanges.ownersRemoved,
                    source = effectiveSource,
                )
            AuditType.UPDATE -> {
                val newName =
                    if (index == 0 && currentAccount != null) {
                        currentAccount.name
                    } else if (index > 0) {
                        entries[index - 1].name
                    } else {
                        entry.name
                    }
                val newCategoryName =
                    if (index == 0 && currentAccount != null) {
                        entries.getOrNull(index - 1)?.categoryName ?: entry.categoryName
                    } else if (index > 0) {
                        entries[index - 1].categoryName
                    } else {
                        entry.categoryName
                    }

                AccountAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name =
                        if (entry.name != newName) {
                            FieldChange.Changed(entry.name, newName)
                        } else {
                            FieldChange.Unchanged(entry.name)
                        },
                    openingDate = FieldChange.Unchanged(entry.openingDate),
                    categoryName =
                        if (entry.categoryName != newCategoryName) {
                            FieldChange.Changed(entry.categoryName, newCategoryName)
                        } else {
                            FieldChange.Unchanged(entry.categoryName)
                        },
                    ownersAdded = ownershipChanges.ownersAdded,
                    ownersRemoved = ownershipChanges.ownersRemoved,
                    source = effectiveSource,
                )
            }
        }
    }
}

@Composable
private fun AccountAuditDiffCard(diff: AccountAuditDiff) {
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
                FieldValueRow("Name", diff.name.value())
                val openingDate = diff.openingDate.value().toLocalDateTime(TimeZone.currentSystemDefault())
                FieldValueRow("Opening Date", "${openingDate.date}")
                FieldValueRow("Category", diff.categoryName.value() ?: "Uncategorized")
                OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved)
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
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue)
                    }
                    val categoryChange = diff.categoryName
                    if (categoryChange is FieldChange.Changed) {
                        FieldChangeRow(
                            "Category",
                            categoryChange.oldValue ?: "Uncategorized",
                            categoryChange.newValue ?: "Uncategorized",
                        )
                    }
                    OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved)
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
                FieldValueRow("Name", diff.name.value(), errorColor)
                val openingDate = diff.openingDate.value().toLocalDateTime(TimeZone.currentSystemDefault())
                FieldValueRow("Opening Date", "${openingDate.date}", errorColor)
                FieldValueRow("Category", diff.categoryName.value() ?: "Uncategorized", errorColor)
                OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun OwnershipChangesSection(
    ownersAdded: List<String>,
    ownersRemoved: List<String>,
) {
    if (ownersAdded.isEmpty() && ownersRemoved.isEmpty()) return

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ownersAdded.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Owners added:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(100.dp),
                )
                Text(
                    text = ownersAdded.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (ownersRemoved.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Owners removed:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(100.dp),
                )
                Text(
                    text = ownersRemoved.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
