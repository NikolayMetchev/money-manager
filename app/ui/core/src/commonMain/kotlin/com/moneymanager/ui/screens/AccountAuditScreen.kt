@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.ui.audit.FieldChange
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun AccountAuditScreen(
    accountId: AccountId,
    auditRepository: AuditRepository,
    accountRepository: AccountRepository,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<AccountAuditEntry>>(emptyList()) }
    var ownershipAuditEntries by remember { mutableStateOf<List<PersonAccountOwnershipAuditEntry>>(emptyList()) }
    var currentAccount by remember { mutableStateOf<Account?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForAccountWithSource(accountId)
            ownershipAuditEntries = auditRepository.getOwnershipAuditHistoryForAccountWithSource(accountId)
            currentAccount = accountRepository.getAccountById(accountId).first()
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to load audit history: ${expected.message}" }
            errorMessage = "Failed to load audit history: ${expected.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Account Audit: ${currentAccount?.name ?: accountId}",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (auditEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No audit history found for this account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val auditDiffs =
                remember(auditEntries, ownershipAuditEntries, currentAccount) {
                    computeAccountAuditDiffs(auditEntries, ownershipAuditEntries, currentAccount)
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.auditId }) { diff ->
                        AccountAuditDiffCard(diff = diff)
                    }
                }
                VerticalScrollbarForLazyList(
                    lazyListState = auditListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

private data class AccountAuditDiff(
    val auditId: Long,
    val auditTimestamp: kotlin.time.Instant,
    val auditType: AuditType,
    val accountId: AccountId,
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
    // Group ownership entries by timestamp proximity to account entries
    // Use a 2-second window to match ownership changes with account changes
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

        // Get source from the first ownership change that has one
        val ownershipSource = matchingOwnershipChanges.firstNotNullOfOrNull { it.source }

        return OwnershipChanges(ownersAdded, ownersRemoved, ownershipSource)
    }

    return entries.mapIndexed { index, entry ->
        val ownershipChanges = findOwnershipChangesForEntry(entry)
        // Use ownership source if account entry doesn't have its own source
        val effectiveSource = entry.source ?: ownershipChanges.source

        when (entry.auditType) {
            AuditType.INSERT ->
                AccountAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    accountId = entry.accountId,
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
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    accountId = entry.accountId,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    openingDate = FieldChange.Deleted(entry.openingDate),
                    categoryName = FieldChange.Deleted(entry.categoryName),
                    ownersAdded = ownershipChanges.ownersAdded,
                    ownersRemoved = ownershipChanges.ownersRemoved,
                    source = effectiveSource,
                )
            AuditType.UPDATE -> {
                // For UPDATE, entry stores OLD values
                // NEW values come from current account (if index 0) or next audit entry
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
                        // Would need category lookup - use entry for now
                        entries.getOrNull(index - 1)?.categoryName ?: entry.categoryName
                    } else if (index > 0) {
                        entries[index - 1].categoryName
                    } else {
                        entry.categoryName
                    }

                AccountAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    accountId = entry.accountId,
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
    val auditDateTime = diff.auditTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())

    val (headerColor, headerText, containerColor) =
        when (diff.auditType) {
            AuditType.INSERT ->
                Triple(
                    MaterialTheme.colorScheme.primary,
                    "Created",
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            AuditType.UPDATE ->
                Triple(
                    MaterialTheme.colorScheme.tertiary,
                    "Updated",
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                )
            AuditType.DELETE ->
                Triple(
                    MaterialTheme.colorScheme.error,
                    "Deleted",
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                )
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = headerColor,
                    )
                    Text(
                        text = "Rev ${diff.revisionId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${auditDateTime.date} ${auditDateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Content
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

@Composable
private fun FieldValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun FieldChangeRow(
    label: String,
    oldValue: String,
    newValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = oldValue,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.LineThrough,
                ),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        )
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = newValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SourceInfoSection(
    source: EntitySource?,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (source == null) return

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )

        when (source.sourceType) {
            SourceType.MANUAL -> {
                val deviceInfo = source.deviceInfo
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Manual (Desktop)")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {
                        FieldValueRow("Origin", "Manual")
                    }
                }
            }
            SourceType.CSV_IMPORT -> {
                val deviceInfo = source.deviceInfo
                FieldValueRow("Origin", "CSV Import")
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {}
                }
            }
            SourceType.SAMPLE_GENERATOR -> {
                val deviceInfo = source.deviceInfo
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {
                        FieldValueRow("Origin", "Sample Generator")
                    }
                }
            }
            SourceType.SYSTEM -> {
                FieldValueRow("Origin", "System")
            }
        }
    }
}
