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
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.audit.FieldChange
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun CurrencyAuditScreen(
    currencyId: CurrencyId,
    auditRepository: AuditRepository,
    currencyRepository: CurrencyRepository,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<CurrencyAuditEntry>>(emptyList()) }
    var currentCurrency by remember { mutableStateOf<Currency?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currencyId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForCurrencyWithSource(currencyId)
            currentCurrency = currencyRepository.getCurrencyById(currencyId).first()
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
                text = "Currency Audit: ${currentCurrency?.code ?: currencyId}",
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
                    text = "No audit history found for this currency.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val auditDiffs =
                remember(auditEntries, currentCurrency) {
                    computeCurrencyAuditDiffs(auditEntries, currentCurrency)
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.auditId }) { diff ->
                        CurrencyAuditDiffCard(diff = diff)
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

private data class CurrencyAuditDiff(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val currencyId: CurrencyId,
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
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    currencyId = entry.currencyId,
                    revisionId = entry.revisionId,
                    code = FieldChange.Created(entry.code),
                    name = FieldChange.Created(entry.name),
                    scaleFactor = FieldChange.Created(entry.scaleFactor),
                    source = entry.source,
                )
            AuditType.DELETE ->
                CurrencyAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    currencyId = entry.currencyId,
                    revisionId = entry.revisionId,
                    code = FieldChange.Deleted(entry.code),
                    name = FieldChange.Deleted(entry.name),
                    scaleFactor = FieldChange.Deleted(entry.scaleFactor),
                    source = entry.source,
                )
            AuditType.UPDATE -> {
                // For UPDATE, entry stores OLD values
                // NEW values come from current currency (if index 0) or next audit entry
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
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    currencyId = entry.currencyId,
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
        }
    }
}
