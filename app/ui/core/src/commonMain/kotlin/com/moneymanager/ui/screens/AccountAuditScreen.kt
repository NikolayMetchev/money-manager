@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAttributeAuditEntry
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.DeletedFinalValuesLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun AccountAuditScreen(
    accountId: AccountId,
    auditRepository: AuditReadRepository,
    accountRepository: AccountWriteRepository,
    categoryRepository: CategoryReadRepository,
    maintenance: Maintenance,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onOwnerClick: (PersonId) -> Unit = {},
    onBack: () -> Unit,
) {
    // All merges where this account is the survivor (reversed or not). The merge/unmerge only modify
    // the merged-away account + the transfers, so the survivor's own audit rows show nothing about
    // them; surface them here so the trail records what happened. Reversible ones also offer undo.
    val mergesIntoThisAccount by accountRepository
        .getMergesForSurvivingAccount(accountId)
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    var mergeToUndo by remember { mutableStateOf<AccountMerge?>(null) }
    val currentAccount by accountRepository
        .getAccountById(accountId)
        .collectAsStateWithSchemaErrorHandling(initial = null)

    AuditScreen(
        defaultTitle = "Account Audit: $accountId",
        entityTypeName = "account",
        loadKey = accountId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForAccount(accountId)
            val ownershipEntries = auditRepository.getOwnershipAuditHistoryForAccount(accountId)
            val account = accountRepository.getAccountById(accountId).first()
            // The UPDATE audit rows store the OLD category; the newest change's NEW category lives only
            // on the live account, so resolve its name here to detect/label the most recent change.
            val currentCategoryName = account?.let { categoryRepository.getCategoryById(it.categoryId).first()?.name }
            val mergeContexts = accountRepository.getMergesForDeletedAccount(accountId)
            val diffs = computeAccountAuditDiffs(entries, ownershipEntries, account, currentCategoryName, mergeContexts)
            AuditScreenData(
                title = "Account Audit: ${account?.name ?: accountId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> AccountAuditDiffCard(diff, onApiSourceClick, onCsvSourceClick, onQifSourceClick, onOwnerClick) },
        header = {
            MergeUndoSection(merges = mergesIntoThisAccount, onUndoClick = { mergeToUndo = it })
        },
    )

    val currentMergeToUndo = mergeToUndo
    if (currentMergeToUndo != null) {
        UnmergeAccountDialog(
            merge = currentMergeToUndo,
            survivingAccountName = currentAccount?.name ?: "this account",
            maintenance = maintenance,
            onDismiss = { mergeToUndo = null },
        )
    }
}

@Composable
private fun MergeUndoSection(
    merges: List<AccountMerge>,
    onUndoClick: (AccountMerge) -> Unit,
) {
    if (merges.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AuditSectionLabel("Merge history")
        merges.forEach { merge ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                val description =
                    "Merged from \"${merge.deletedAccountName}\" · ${merge.transferCount} transaction(s)" +
                        if (merge.reversed) " — undone" else ""
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!merge.reversed) {
                    TextButton(onClick = { onUndoClick(merge) }) {
                        Text("Undo merge")
                    }
                }
            }
        }
    }
}

internal data class AccountAuditDiff(
    val id: Long,
    val auditTimestamp: kotlin.time.Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val openingDate: FieldChange<kotlin.time.Instant>,
    val categoryName: FieldChange<String?>,
    val ownersAdded: List<PersonAccountOwnershipAuditEntry>,
    val ownersRemoved: List<PersonAccountOwnershipAuditEntry>,
    val attributeChanges: List<AccountAttributeAuditEntry>,
    val source: SourceRecord?,
    val mergeNote: String? = null,
) {
    val hasFieldChanges: Boolean
        get() = listOf(name, openingDate, categoryName).any { it is FieldChange.Changed }

    val hasOwnershipChanges: Boolean
        get() = ownersAdded.isNotEmpty() || ownersRemoved.isNotEmpty()

    val hasChanges: Boolean
        get() = hasFieldChanges || hasOwnershipChanges || attributeChanges.isNotEmpty()
}

internal fun computeAccountAuditDiffs(
    entries: List<AccountAuditEntry>,
    ownershipEntries: List<PersonAccountOwnershipAuditEntry>,
    currentAccount: Account?,
    currentCategoryName: String?,
    mergeContexts: List<AccountMergeContext>,
): List<AccountAuditDiff> {
    val timestampWindowMs = 2000L

    // The merge deleted this account at deleted_account_revision_id; the undo recreated it at the next
    // revision. Label those audit entries so the trail reads as a merge/undo rather than a bare
    // delete/create. Matched by revision, which is exact (no fragile timestamp window).
    fun mergeNoteFor(entry: AccountAuditEntry): String? =
        when (entry.auditType) {
            AuditType.DELETE ->
                mergeContexts
                    .firstOrNull { it.deletedAccountRevisionId == entry.revisionId }
                    ?.let { "Merged into \"${it.survivingAccountName ?: "another account"}\"" }
            AuditType.INSERT ->
                mergeContexts
                    .firstOrNull { it.reversed && it.deletedAccountRevisionId + 1 == entry.revisionId }
                    ?.let { "Restored — merge with \"${it.survivingAccountName ?: "another account"}\" undone" }
            AuditType.UPDATE -> null
        }

    data class OwnershipChanges(
        val ownersAdded: List<PersonAccountOwnershipAuditEntry>,
        val ownersRemoved: List<PersonAccountOwnershipAuditEntry>,
        val source: SourceRecord?,
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
                .toList()

        val ownersRemoved =
            matchingOwnershipChanges
                .filter { it.auditType == AuditType.DELETE }
                .toList()

        val ownershipSource = matchingOwnershipChanges.firstNotNullOfOrNull { it.source }

        return OwnershipChanges(ownersAdded, ownersRemoved, ownershipSource)
    }

    // A standalone ownership change (adding/removing an owner) doesn't touch the account row, so it
    // produces no account audit entry to attach to. Surface such orphans as their own entries below.
    fun isWithinWindow(
        a: kotlin.time.Instant,
        b: kotlin.time.Instant,
    ): Boolean = kotlin.math.abs(a.toEpochMilliseconds() - b.toEpochMilliseconds()) <= timestampWindowMs

    fun ownershipOnlyDiff(group: List<PersonAccountOwnershipAuditEntry>): AccountAuditDiff {
        val first = group.first()
        val fallback = entries.firstOrNull()
        return AccountAuditDiff(
            // Negative id: account_audit ids are positive, so this can't collide as a LazyColumn key.
            id = -first.id,
            auditTimestamp = first.auditTimestamp,
            auditType = AuditType.UPDATE,
            revisionId = first.revisionId,
            name = FieldChange.Unchanged(currentAccount?.name ?: fallback?.name ?: ""),
            openingDate = FieldChange.Unchanged(currentAccount?.openingDate ?: fallback?.openingDate ?: first.auditTimestamp),
            categoryName = FieldChange.Unchanged(currentCategoryName),
            ownersAdded = group.filter { it.auditType == AuditType.INSERT },
            ownersRemoved = group.filter { it.auditType == AuditType.DELETE },
            attributeChanges = emptyList(),
            source = group.firstNotNullOfOrNull { it.source },
        )
    }

    val accountDiffs =
        entries.mapIndexed { index, entry ->
            val ownershipChanges = findOwnershipChangesForEntry(entry)
            val effectiveSource = entry.source ?: ownershipChanges.source
            val mergeNote = mergeNoteFor(entry)

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
                        attributeChanges = entry.attributeChanges,
                        source = effectiveSource,
                        mergeNote = mergeNote,
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
                        attributeChanges = entry.attributeChanges,
                        source = effectiveSource,
                        mergeNote = mergeNote,
                    )
                AuditType.UPDATE -> {
                    val previousEntry = entries.getOrNull(index - 1)

                    AccountAuditDiff(
                        id = entry.id,
                        auditTimestamp = entry.auditTimestamp,
                        auditType = entry.auditType,
                        revisionId = entry.revisionId,
                        name =
                            resolveUpdateChange(
                                index = index,
                                currentEntry = currentAccount,
                                previousEntry = previousEntry,
                                entryValue = entry.name,
                                currentValue = { it.name },
                                previousValue = { it.name },
                            ),
                        openingDate = FieldChange.Unchanged(entry.openingDate),
                        categoryName =
                            resolveUpdateChange(
                                index = index,
                                currentEntry = currentAccount,
                                previousEntry = previousEntry,
                                entryValue = entry.categoryName,
                                // currentAccount only carries categoryId; the resolved current name is
                                // captured here so the newest change compares against the live category.
                                currentValue = { currentCategoryName },
                                previousValue = { it.categoryName },
                            ),
                        ownersAdded = ownershipChanges.ownersAdded,
                        ownersRemoved = ownershipChanges.ownersRemoved,
                        attributeChanges = entry.attributeChanges,
                        source = effectiveSource,
                    )
                }
            }
        }

    val matchedOwnershipIds =
        entries.flatMapTo(mutableSetOf()) { entry ->
            ownershipEntries
                .filter { isWithinWindow(it.auditTimestamp, entry.auditTimestamp) }
                .map { it.id }
        }
    val ownershipOnlyDiffs =
        ownershipEntries
            .filterNot { it.id in matchedOwnershipIds }
            .groupBy { it.auditTimestamp.toEpochMilliseconds() }
            .map { (_, group) -> ownershipOnlyDiff(group) }

    return (accountDiffs + ownershipOnlyDiffs)
        .sortedWith(compareByDescending<AccountAuditDiff> { it.auditTimestamp }.thenByDescending { it.id })
}

@Composable
private fun AccountAuditDiffCard(
    diff: AccountAuditDiff,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onOwnerClick: (PersonId) -> Unit = {},
) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        diff.mergeNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        when (diff.auditType) {
            AuditType.INSERT -> {
                AuditSectionLabel("Created with:")
                FieldValueRow("Name", diff.name.value())
                val openingDate = diff.openingDate.value().toLocalDateTime(TimeZone.currentSystemDefault())
                FieldValueRow("Opening Date", "${openingDate.date}")
                FieldValueRow("Category", diff.categoryName.value() ?: "Uncategorized")
                OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved, onOwnerClick)
                AccountAttributeChangesSection(diff.attributeChanges)
                SourceInfoSection(
                    diff.source,
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
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
                    val categoryChange = diff.categoryName
                    if (categoryChange is FieldChange.Changed) {
                        FieldChangeRow(
                            "Category",
                            categoryChange.oldValue ?: "Uncategorized",
                            categoryChange.newValue ?: "Uncategorized",
                        )
                    }
                    OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved, onOwnerClick)
                    AccountAttributeChangesSection(diff.attributeChanges)
                }
                SourceInfoSection(
                    diff.source,
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
            }
            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                DeletedFinalValuesLabel(errorColor)
                FieldValueRow("Name", diff.name.value(), errorColor)
                val openingDate = diff.openingDate.value().toLocalDateTime(TimeZone.currentSystemDefault())
                FieldValueRow("Opening Date", "${openingDate.date}", errorColor)
                FieldValueRow("Category", diff.categoryName.value() ?: "Uncategorized", errorColor)
                OwnershipChangesSection(diff.ownersAdded, diff.ownersRemoved, onOwnerClick)
                AccountAttributeChangesSection(diff.attributeChanges, errorColor)
                SourceInfoSection(
                    diff.source,
                    labelColor = errorColor.copy(alpha = 0.8f),
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
            }
        }
    }
}

@Composable
private fun OwnershipChangesSection(
    ownersAdded: List<PersonAccountOwnershipAuditEntry>,
    ownersRemoved: List<PersonAccountOwnershipAuditEntry>,
    onOwnerClick: (PersonId) -> Unit,
) {
    if (ownersAdded.isEmpty() && ownersRemoved.isEmpty()) return

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ownersAdded.isNotEmpty()) {
            OwnershipLinkRow(
                ownerships = ownersAdded,
                color = MaterialTheme.colorScheme.primary,
                onOwnerClick = onOwnerClick,
            )
        }
        if (ownersRemoved.isNotEmpty()) {
            OwnershipLinkRow(
                ownerships = ownersRemoved,
                color = MaterialTheme.colorScheme.error,
                onOwnerClick = onOwnerClick,
            )
        }
    }
}

@Composable
private fun OwnershipLinkRow(
    ownerships: List<PersonAccountOwnershipAuditEntry>,
    color: Color,
    onOwnerClick: (PersonId) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Owners:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ownerships.forEachIndexed { index, ownership ->
                if (index > 0) {
                    Text(text = ", ", style = MaterialTheme.typography.bodyMedium, color = color)
                }
                val ownerLabel = ownership.personFullName ?: "Unknown (ID: ${ownership.personId.id})"
                Text(
                    text = ownerLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.clickable { onOwnerClick(ownership.personId) },
                )
            }
        }
    }
}

@Composable
private fun AccountAttributeChangesSection(
    attributeChanges: List<AccountAttributeAuditEntry>,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (attributeChanges.isEmpty()) return

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Attributes:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attributeChanges.forEach { entry ->
            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val prefix =
                    when (entry.auditType) {
                        AuditType.INSERT -> "+"
                        AuditType.DELETE -> "-"
                        AuditType.UPDATE -> "~"
                    }
                val color =
                    when (entry.auditType) {
                        AuditType.INSERT -> MaterialTheme.colorScheme.primary
                        AuditType.DELETE -> MaterialTheme.colorScheme.error
                        AuditType.UPDATE -> valueColor
                    }
                val timestamp = entry.auditTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "$prefix${entry.attributeType.name}:",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                Text(
                    text = "${entry.value} @ ${timestamp.date} ${timestamp.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}
