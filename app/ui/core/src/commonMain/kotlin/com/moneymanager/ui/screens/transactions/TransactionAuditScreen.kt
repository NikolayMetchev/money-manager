package com.moneymanager.ui.screens.transactions

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
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.audit.AttributeChange
import com.moneymanager.ui.audit.AuditEntryDiff
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.UpdateNewValues
import com.moneymanager.ui.audit.computeAuditDiff
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun TransactionAuditScreen(
    transferId: TransferId,
    auditRepository: AuditRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    currentDeviceId: DeviceId? = null,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<TransferAuditEntry>>(emptyList()) }
    var currentTransfer by remember { mutableStateOf<Transfer?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    LaunchedEffect(transferId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForTransfer(transferId)
            val transfer = transactionRepository.getTransactionById(transferId.id).first()
            currentTransfer = transfer
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
                text = "Audit History: $transferId",
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
                    text = "No audit history found for this transaction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Compute diffs from audit entries
            // For UPDATE entries: entry stores OLD values, NEW values come from:
            // - Current transfer (if this is the most recent entry, index 0)
            // - Next audit entry's values (entries[index-1]) for older updates
            // Attribute changes are now stored directly in TransferAttributeAudit,
            // so we don't need to compare attributes between entries.
            val auditDiffs =
                remember(auditEntries, currentTransfer) {
                    val transfer = currentTransfer
                    auditEntries.mapIndexed { index, entry ->
                        val newValuesForUpdate =
                            when {
                                entry.auditType != AuditType.UPDATE -> null
                                index == 0 && transfer != null ->
                                    UpdateNewValues.fromTransfer(transfer)
                                index > 0 ->
                                    UpdateNewValues.fromAuditEntry(auditEntries[index - 1])
                                else -> null
                            }
                        computeAuditDiff(entry, newValuesForUpdate)
                    }
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.id }) { diff ->
                        AuditDiffCard(
                            diff = diff,
                            accounts = accounts,
                            currentDeviceId = currentDeviceId,
                        )
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

@Composable
private fun AuditDiffCard(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: DeviceId? = null,
) {
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
            // Header row: Type + Revision + Timestamp
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

            // Content varies by audit type
            when (diff.auditType) {
                AuditType.INSERT -> InsertDiffContent(diff, accounts, currentDeviceId)
                AuditType.UPDATE -> UpdateDiffContent(diff, accounts, currentDeviceId)
                AuditType.DELETE -> DeleteDiffContent(diff, accounts, currentDeviceId)
            }
        }
    }
}

@Composable
private fun InsertDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: DeviceId? = null,
) {
    val transactionDateTime = diff.timestamp.value().toLocalDateTime(TimeZone.currentSystemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Created with:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}")
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts))
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts))
        FieldValueRow("Amount", formatAmount(diff.amount.value()))
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" })
        AttributesSection(diff.attributeChanges)
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId)
    }
}

@Composable
private fun UpdateDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: DeviceId? = null,
) {
    val hasAnyChanges = diff.hasChanges

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!hasAnyChanges) {
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
            val timestampChange = diff.timestamp
            if (timestampChange is FieldChange.Changed) {
                val oldDateTime = timestampChange.oldValue.toLocalDateTime(TimeZone.currentSystemDefault())
                val newDateTime = timestampChange.newValue.toLocalDateTime(TimeZone.currentSystemDefault())
                val timeDiff = formatTimeDiff(timestampChange.oldValue, timestampChange.newValue)
                FieldChangeRow(
                    label = "Date",
                    oldValue = "${oldDateTime.date} ${oldDateTime.time}",
                    newValue = "${newDateTime.date} ${newDateTime.time}",
                    suffix = "($timeDiff)",
                )
            }
            val sourceChange = diff.sourceAccountId
            if (sourceChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "From",
                    oldValue = resolveAccountName(sourceChange.oldValue, accounts),
                    newValue = resolveAccountName(sourceChange.newValue, accounts),
                )
            }
            val targetChange = diff.targetAccountId
            if (targetChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "To",
                    oldValue = resolveAccountName(targetChange.oldValue, accounts),
                    newValue = resolveAccountName(targetChange.newValue, accounts),
                )
            }
            val amountChange = diff.amount
            if (amountChange is FieldChange.Changed) {
                val amountDiff = amountChange.newValue - amountChange.oldValue
                val sign = if (amountDiff.isPositive()) "+" else ""
                FieldChangeRow(
                    label = "Amount",
                    oldValue = formatAmount(amountChange.oldValue),
                    newValue = formatAmount(amountChange.newValue),
                    suffix = "($sign${formatAmount(amountDiff)})",
                )
            }
            val descriptionChange = diff.description
            if (descriptionChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "Description",
                    oldValue = descriptionChange.oldValue.ifBlank { "(none)" },
                    newValue = descriptionChange.newValue.ifBlank { "(none)" },
                )
            }
            // Show attribute changes (added, removed, changed - not unchanged)
            val significantAttrChanges = diff.attributeChanges.filter { it !is AttributeChange.Unchanged }
            if (significantAttrChanges.isNotEmpty()) {
                AttributeChangesSection(significantAttrChanges)
            }
        }
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId)
    }
}

@Composable
private fun DeleteDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: DeviceId? = null,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val transactionDateTime = diff.timestamp.value().toLocalDateTime(TimeZone.currentSystemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Deleted (final values):",
            style = MaterialTheme.typography.labelMedium,
            color = errorColor.copy(alpha = 0.8f),
        )
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}", errorColor)
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts), errorColor)
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts), errorColor)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), errorColor)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, errorColor)
        AttributesSection(diff.attributeChanges, errorColor)
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId, labelColor = errorColor.copy(alpha = 0.8f))
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
            modifier = Modifier.width(80.dp),
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
    suffix: String? = null,
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
            modifier = Modifier.width(80.dp),
        )

        // Old value with strikethrough
        Text(
            text = oldValue,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.LineThrough,
                ),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        )

        // Arrow
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // New value
        Text(
            text = newValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Optional suffix (e.g., diff in brackets)
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttributesSection(
    attributeChanges: List<AttributeChange>,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (attributeChanges.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Attributes:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attributeChanges.forEach { change ->
            val value =
                when (change) {
                    is AttributeChange.Added -> change.value
                    is AttributeChange.Removed -> change.value
                    is AttributeChange.Changed -> change.newValue
                    is AttributeChange.ModifiedFrom -> change.oldValue
                    is AttributeChange.Unchanged -> change.value
                }
            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${change.attributeTypeName}:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                )
            }
        }
    }
}

@Composable
private fun AttributeChangesSection(attributeChanges: List<AttributeChange>) {
    if (attributeChanges.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        attributeChanges.forEach { change ->
            when (change) {
                is AttributeChange.Added -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "+${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = change.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is AttributeChange.Removed -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "-${change.attributeTypeName}:",
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = change.value,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                }
                is AttributeChange.Changed -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.oldValue,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "\u2192",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.newValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is AttributeChange.ModifiedFrom -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.oldValue,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "\u2192 ?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is AttributeChange.Unchanged -> {
                    // Don't show unchanged attributes in the changes section
                }
            }
        }
    }
}

@Composable
private fun SourceInfoSection(
    source: TransferSource?,
    currentDeviceId: DeviceId? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (source == null) return

    val isThisDevice = currentDeviceId != null && source.deviceId == currentDeviceId.id

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
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Manual (Desktop)$thisDeviceSuffix")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)$thisDeviceSuffix")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
            SourceType.CSV_IMPORT -> {
                val csvSource = source.csvSource
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                if (csvSource != null) {
                    val fileName = csvSource.fileName ?: "Unknown file"
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix")
                    FieldValueRow("File", fileName)
                    FieldValueRow("Row", (csvSource.rowIndex + 1).toString())
                } else {
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix")
                }
                // Show device info for CSV imports too
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
            SourceType.SAMPLE_GENERATOR -> {
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)$thisDeviceSuffix")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)$thisDeviceSuffix")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
            SourceType.SYSTEM -> {
                FieldValueRow("Origin", "System")
            }
        }
    }
}
