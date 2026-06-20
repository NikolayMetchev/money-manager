package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
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
    auditRepository: AuditReadRepository,
    accountRepository: AccountWriteRepository,
    transactionRepository: TransactionReadRepository,
    currentDeviceId: DeviceId? = null,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onAccountClick: (AccountId) -> Unit = {},
    onBack: () -> Unit,
) {
    val accounts by accountRepository
        .getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Last-known names for accounts that no longer exist (e.g. merged-away/deleted), so the audit
    // trail can show a real name instead of a bare id and avoid linking to a missing account.
    var auditedAccountNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(transferId) {
        auditedAccountNames = auditRepository.getLatestAuditedAccountNames()
    }

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
                auditedAccountNames = auditedAccountNames,
                currentDeviceId = currentDeviceId,
                onCsvSourceClick = onCsvSourceClick,
                onQifSourceClick = onQifSourceClick,
                onApiSourceClick = onApiSourceClick,
                onAccountClick = onAccountClick,
            )
        },
    )
}

@Composable
private fun TransactionAuditDiffCard(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    currentDeviceId: DeviceId? = null,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onAccountClick: (AccountId) -> Unit = {},
) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT ->
                InsertDiffContent(
                    diff,
                    accounts,
                    auditedAccountNames,
                    currentDeviceId,
                    onCsvSourceClick,
                    onQifSourceClick,
                    onApiSourceClick,
                    onAccountClick,
                )
            AuditType.UPDATE ->
                UpdateDiffContent(
                    diff,
                    accounts,
                    auditedAccountNames,
                    currentDeviceId,
                    onCsvSourceClick,
                    onQifSourceClick,
                    onApiSourceClick,
                    onAccountClick,
                )
            AuditType.DELETE ->
                DeleteDiffContent(
                    diff,
                    accounts,
                    auditedAccountNames,
                    currentDeviceId,
                    onCsvSourceClick,
                    onQifSourceClick,
                    onApiSourceClick,
                    onAccountClick,
                )
        }
    }
}

@Composable
private fun InsertDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    currentDeviceId: DeviceId? = null,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onAccountClick: (AccountId) -> Unit = {},
) {
    val transactionDateTime = diff.timestamp.value().toLocalDateTime(TimeZone.currentSystemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Created with:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}", labelWidth = LABEL_WIDTH)
        AccountLinkRow("From", diff.sourceAccountId.value(), accounts, auditedAccountNames, onAccountClick)
        AccountLinkRow("To", diff.targetAccountId.value(), accounts, auditedAccountNames, onAccountClick)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), labelWidth = LABEL_WIDTH)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, labelWidth = LABEL_WIDTH)
        AttributesSection(diff.attributeChanges)
        SourceInfoSection(
            diff.source,
            currentDeviceId = currentDeviceId,
            onCsvSourceClick = onCsvSourceClick,
            onQifSourceClick = onQifSourceClick,
            onApiSourceClick = onApiSourceClick,
        )
    }
}

@Composable
private fun UpdateDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    currentDeviceId: DeviceId? = null,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onAccountClick: (AccountId) -> Unit = {},
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
                AccountChangeRow("From", sourceChange.oldValue, sourceChange.newValue, accounts, auditedAccountNames, onAccountClick)
            }
            val targetChange = diff.targetAccountId
            if (targetChange is FieldChange.Changed) {
                AccountChangeRow("To", targetChange.oldValue, targetChange.newValue, accounts, auditedAccountNames, onAccountClick)
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
            if (diff.attributeChanges.isNotEmpty()) {
                AttributeChangesSection(diff.attributeChanges)
            }
        }
        SourceInfoSection(
            diff.source,
            currentDeviceId = currentDeviceId,
            onCsvSourceClick = onCsvSourceClick,
            onQifSourceClick = onQifSourceClick,
            onApiSourceClick = onApiSourceClick,
        )
    }
}

@Composable
private fun DeleteDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    currentDeviceId: DeviceId? = null,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onAccountClick: (AccountId) -> Unit = {},
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
        AccountLinkRow("From", diff.sourceAccountId.value(), accounts, auditedAccountNames, onAccountClick)
        AccountLinkRow("To", diff.targetAccountId.value(), accounts, auditedAccountNames, onAccountClick)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), errorColor, labelWidth = LABEL_WIDTH)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, errorColor, labelWidth = LABEL_WIDTH)
        AttributesSection(diff.attributeChanges, errorColor)
        SourceInfoSection(
            diff.source,
            currentDeviceId = currentDeviceId,
            labelColor = errorColor.copy(alpha = 0.8f),
            onCsvSourceClick = onCsvSourceClick,
            onQifSourceClick = onQifSourceClick,
            onApiSourceClick = onApiSourceClick,
        )
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
            }
        }
    }
}

@Composable
private fun SourceInfoSection(
    source: SourceRecord?,
    currentDeviceId: DeviceId? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
) {
    if (source == null) return

    val isThisDevice = currentDeviceId != null && source.deviceId == currentDeviceId.id
    val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )

        when (val origin = source.source) {
            Source.Manual -> {
                when (val deviceInfo = source.deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Manual (Desktop)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
                        FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
                    }
                    null -> FieldValueRow("Origin", "Manual$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                }
            }
            is Source.Csv -> {
                val fileName = source.fileName ?: "Unknown file"
                val rowIndex = origin.rowIndex ?: 0
                FieldValueRow("Origin", "CSV Import$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                CsvSourceLinkRow(
                    label = "File",
                    value = fileName,
                    csvImportId = origin.importId,
                    rowIndex = rowIndex,
                    onCsvSourceClick = onCsvSourceClick,
                )
                CsvSourceLinkRow(
                    label = "Row",
                    value = rowIndex.toString(),
                    csvImportId = origin.importId,
                    rowIndex = rowIndex,
                    onCsvSourceClick = onCsvSourceClick,
                )
                DeviceInfoFields(source.deviceInfo)
            }
            is Source.Qif -> {
                val recordIndex = origin.recordIndex
                FieldValueRow("Origin", "QIF Import$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                // The File link always opens the QIF import; only show a Record link when a single
                // originating record is known (file-level provenance has none).
                QifSourceLinkRow(
                    label = "File",
                    value = source.fileName ?: "Unknown file",
                    qifImportId = origin.importId,
                    recordIndex = recordIndex,
                    onQifSourceClick = onQifSourceClick,
                )
                if (recordIndex != null) {
                    QifSourceLinkRow(
                        label = "Record",
                        value = recordIndex.toString(),
                        qifImportId = origin.importId,
                        recordIndex = recordIndex,
                        onQifSourceClick = onQifSourceClick,
                    )
                }
                DeviceInfoFields(source.deviceInfo)
            }
            Source.SampleGenerator -> {
                when (val deviceInfo = source.deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
                        FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
                    }
                    null -> FieldValueRow("Origin", "Sample Generator$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                }
            }
            Source.System -> {
                FieldValueRow("Origin", "System", labelWidth = LABEL_WIDTH)
            }
            Source.Merge, Source.Unmerge -> {
                val originLabel = if (origin == Source.Merge) "Merge" else "Undo Merge"
                FieldValueRow("Origin", "$originLabel$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                DeviceInfoFields(source.deviceInfo)
            }
            is Source.Api -> {
                FieldValueRow("Origin", "API Import$thisDeviceSuffix", labelWidth = LABEL_WIDTH)
                val requestId = origin.requestId
                val jsonPath = origin.jsonPath
                if (requestId != null && jsonPath != null) {
                    ApiSourceLinkRow(
                        label = "Session",
                        value = origin.sessionId.id.toString(),
                        sessionId = origin.sessionId,
                        requestId = requestId,
                        jsonPath = jsonPath.value,
                        onApiSourceClick = onApiSourceClick,
                    )
                    FieldValueRow("Request", requestId.id.toString(), labelWidth = LABEL_WIDTH)
                    ApiSourceLinkRow(
                        label = "JSON Path",
                        value = jsonPath.value,
                        sessionId = origin.sessionId,
                        requestId = requestId,
                        jsonPath = jsonPath.value,
                        onApiSourceClick = onApiSourceClick,
                    )
                }
                DeviceInfoFields(source.deviceInfo)
            }
        }
    }
}

@Composable
private fun DeviceInfoFields(deviceInfo: DeviceInfo?) {
    when (deviceInfo) {
        is DeviceInfo.Jvm -> {
            FieldValueRow("Machine", deviceInfo.machineName, labelWidth = LABEL_WIDTH)
            FieldValueRow("OS", deviceInfo.osName, labelWidth = LABEL_WIDTH)
        }
        is DeviceInfo.Android -> {
            FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = LABEL_WIDTH)
        }
        null -> Unit
    }
}

/**
 * Renders an account reference in the audit trail as "Name (#id)". Accounts that still exist are
 * clickable links; accounts that no longer exist (e.g. merged-away/deleted) are shown struck-through
 * and non-clickable, labelled with their last-known name from the audit history.
 */
@Composable
private fun AccountReference(
    accountId: AccountId,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    onAccountClick: (AccountId) -> Unit,
    struckThrough: Boolean = false,
    struckThroughColor: Color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
) {
    val exists = accounts.any { it.id == accountId }
    val label = accountAuditLabel(accountId, accounts, auditedAccountNames)
    // A struck-through "old value" stays struck through whether or not the account still exists;
    // a deleted account is always struck through. Deleted accounts are never clickable.
    val showStrikethrough = struckThrough || !exists
    val textStyle =
        if (showStrikethrough) {
            MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough)
        } else {
            MaterialTheme.typography.bodyMedium
        }
    if (exists) {
        TextButton(
            onClick = { onAccountClick(accountId) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                text = label,
                style = textStyle,
                color = if (struckThrough) struckThroughColor else Color.Unspecified,
            )
        }
    } else {
        Text(
            text = label,
            style = textStyle,
            color = struckThroughColor,
        )
    }
}

/** "Name (#id)" for an existing or audited account, or "#id" when even the name is unknown. */
private fun accountAuditLabel(
    accountId: AccountId,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
): String {
    val name = accounts.find { it.id == accountId }?.name ?: auditedAccountNames[accountId.id]
    return if (name != null) "$name (#${accountId.id})" else "#${accountId.id}"
}

@Composable
private fun AccountLinkRow(
    label: String,
    accountId: AccountId,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    onAccountClick: (AccountId) -> Unit,
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
            modifier = Modifier.width(LABEL_WIDTH),
        )
        AccountReference(accountId, accounts, auditedAccountNames, onAccountClick)
    }
}

@Composable
private fun AccountChangeRow(
    label: String,
    oldAccountId: AccountId,
    newAccountId: AccountId,
    accounts: List<Account>,
    auditedAccountNames: Map<Long, String>,
    onAccountClick: (AccountId) -> Unit,
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
            modifier = Modifier.width(LABEL_WIDTH),
        )
        // The old account is struck through; if it was deleted (e.g. merged away) it is also non-clickable.
        AccountReference(
            accountId = oldAccountId,
            accounts = accounts,
            auditedAccountNames = auditedAccountNames,
            onAccountClick = onAccountClick,
            struckThrough = true,
        )
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccountReference(newAccountId, accounts, auditedAccountNames, onAccountClick)
    }
}

@Composable
private fun CsvSourceLinkRow(
    label: String,
    value: String,
    csvImportId: CsvImportId,
    rowIndex: Long,
    onCsvSourceClick: (CsvImportId, Long) -> Unit,
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
            modifier = Modifier.width(LABEL_WIDTH),
        )
        TextButton(
            onClick = { onCsvSourceClick(csvImportId, rowIndex) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(value)
        }
    }
}

@Composable
private fun QifSourceLinkRow(
    label: String,
    value: String,
    qifImportId: QifImportId,
    recordIndex: Long?,
    onQifSourceClick: (QifImportId, Long?) -> Unit,
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
            modifier = Modifier.width(LABEL_WIDTH),
        )
        TextButton(
            onClick = { onQifSourceClick(qifImportId, recordIndex) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(value)
        }
    }
}

@Composable
private fun ApiSourceLinkRow(
    label: String,
    value: String,
    sessionId: ApiSessionId,
    requestId: ApiRequestId,
    jsonPath: String,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit,
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
            modifier = Modifier.width(LABEL_WIDTH),
        )
        TextButton(
            onClick = { onApiSourceClick(sessionId, requestId, jsonPath) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(value)
        }
    }
}
