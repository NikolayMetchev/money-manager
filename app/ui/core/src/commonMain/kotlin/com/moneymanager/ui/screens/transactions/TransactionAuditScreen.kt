package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.audit.AttributeChange
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditEntryDiff
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.UpdateNewValues
import com.moneymanager.ui.audit.computeAuditDiff
import com.moneymanager.ui.audit.reverseAttributeChanges
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val LABEL_WIDTH = 80.dp

@Composable
fun TransactionAuditScreen(
    transferId: TransferId,
    auditRepository: AuditRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    currentDeviceId: DeviceId? = null,
    onBack: () -> Unit,
) {
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    AuditScreen(
        defaultTitle = "Audit History: $transferId",
        entityTypeName = "transaction",
        loadKey = transferId,
        loadData = {
            val auditEntries = auditRepository.getAuditHistoryForTransfer(transferId)
            val transfer = transactionRepository.getTransactionById(transferId.id).first()

            // Pre-compute attribute state at each revision by walking backwards.
            // attributeStates[i] = attribute state AFTER entry[i]'s changes were applied.
            // Entry[0] is the most recent, so attributeStates[0] = current transfer's attributes.
            val attributeStates = mutableListOf<Map<String, String>>()
            var currentAttrs = transfer?.attributes?.associate { it.attributeType.name to it.value }.orEmpty()
            for (entry in auditEntries) {
                attributeStates.add(currentAttrs)
                currentAttrs = reverseAttributeChanges(currentAttrs, entry)
            }

            val diffs =
                auditEntries.mapIndexed { index, entry ->
                    val newValuesForUpdate =
                        when {
                            entry.auditType != AuditType.UPDATE -> null
                            index == 0 && transfer != null ->
                                UpdateNewValues.fromTransfer(transfer)
                            index > 0 ->
                                UpdateNewValues.fromAuditEntry(
                                    auditEntries[index - 1],
                                    attributeValues = attributeStates[index - 1],
                                )
                            else -> null
                        }
                    computeAuditDiff(entry, newValuesForUpdate)
                }
            AuditScreenData(
                title = "Audit History: $transferId",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff ->
            TransactionAuditDiffCard(
                diff = diff,
                accounts = accounts,
                currentDeviceId = currentDeviceId,
            )
        },
    )
}

@Composable
private fun TransactionAuditDiffCard(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: DeviceId? = null,
) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> InsertDiffContent(diff, accounts, currentDeviceId)
            AuditType.UPDATE -> UpdateDiffContent(diff, accounts, currentDeviceId)
            AuditType.DELETE -> DeleteDiffContent(diff, accounts, currentDeviceId)
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
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}", labelWidth = LABEL_WIDTH)
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts), labelWidth = LABEL_WIDTH)
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts), labelWidth = LABEL_WIDTH)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), labelWidth = LABEL_WIDTH)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, labelWidth = LABEL_WIDTH)
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
                    labelWidth = LABEL_WIDTH,
                )
            }
            val sourceChange = diff.sourceAccountId
            if (sourceChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "From",
                    oldValue = resolveAccountName(sourceChange.oldValue, accounts),
                    newValue = resolveAccountName(sourceChange.newValue, accounts),
                    labelWidth = LABEL_WIDTH,
                )
            }
            val targetChange = diff.targetAccountId
            if (targetChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "To",
                    oldValue = resolveAccountName(targetChange.oldValue, accounts),
                    newValue = resolveAccountName(targetChange.newValue, accounts),
                    labelWidth = LABEL_WIDTH,
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
                    labelWidth = LABEL_WIDTH,
                )
            }
            val descriptionChange = diff.description
            if (descriptionChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "Description",
                    oldValue = descriptionChange.oldValue.ifBlank { "(none)" },
                    newValue = descriptionChange.newValue.ifBlank { "(none)" },
                    labelWidth = LABEL_WIDTH,
                )
            }
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
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}", errorColor, labelWidth = LABEL_WIDTH)
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts), errorColor, labelWidth = LABEL_WIDTH)
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts), errorColor, labelWidth = LABEL_WIDTH)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), errorColor, labelWidth = LABEL_WIDTH)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, errorColor, labelWidth = LABEL_WIDTH)
        AttributesSection(diff.attributeChanges, errorColor)
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId, labelColor = errorColor.copy(alpha = 0.8f))
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
                        FieldValueRow("Origin", "Manual (Desktop)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
                        FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
                    }
                }
            }
            SourceType.CSV_IMPORT -> {
                val csvSource = source.csvSource
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                if (csvSource != null) {
                    val fileName = csvSource.fileName ?: "Unknown file"
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                    FieldValueRow("File", fileName, labelWidth = LABEL_WIDTH)
                    FieldValueRow("Row", (csvSource.rowIndex + 1).toString(), labelWidth = LABEL_WIDTH)
                } else {
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                }
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
                        FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
                    }
                }
            }
            SourceType.SAMPLE_GENERATOR -> {
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
                        FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
                    }
                }
            }
            SourceType.SYSTEM -> {
                FieldValueRow("Origin", "System", labelWidth = LABEL_WIDTH)
            }
        }
    }
}
