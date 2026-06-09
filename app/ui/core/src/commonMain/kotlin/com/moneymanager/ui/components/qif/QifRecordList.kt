package com.moneymanager.ui.components.qif

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.qif.QifImportRecord

/**
 * Renders parsed QIF records as expandable cards (the raw-file view). Unlike CSV's tabular grid,
 * QIF records are structured: each card shows the parsed fields, any splits, and the verbatim QIF
 * text. Unsupported records (investments / unknown sections) are badged and not importable.
 */
@Composable
fun QifRecordList(
    records: List<QifImportRecord>,
    onTransferClick: (TransferId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = { it.recordIndex }) { record ->
            QifRecordCard(record = record, onTransferClick = onTransferClick)
        }
    }
}

@Composable
private fun QifRecordCard(
    record: QifImportRecord,
    onTransferClick: (TransferId, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val containerColor =
        when {
            !record.supported -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            record.importStatus == ImportStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            record.transferId != null -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.payee?.takeIf { it.isNotBlank() } ?: record.memo?.takeIf { it.isNotBlank() } ?: "Record ${record.recordIndex}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text =
                            buildString {
                                record.date?.let { append(it) }
                                record.category?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                record.transferAccount?.takeIf { it.isNotBlank() }?.let { append(" · [$it]") }
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    record.amount?.let { amount ->
                        Text(text = amount, style = MaterialTheme.typography.titleSmall)
                    }
                    RecordBadge(record)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (record.splits.isNotEmpty()) {
                    Text("Splits", style = MaterialTheme.typography.labelMedium)
                    record.splits.forEach { split ->
                        Text(
                            text =
                                buildString {
                                    append(split.amount ?: "")
                                    split.category?.let { append("  $it") }
                                    split.transferAccount?.let { append("  [$it]") }
                                    split.memo?.let { append("  — $it") }
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = record.rawText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record.transferId?.let { transferId ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "View linked transaction",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clickable {
                                onTransferClick(transferId, !(record.amount ?: "").trim().startsWith("-"))
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordBadge(record: QifImportRecord) {
    val (label, container, content) =
        when {
            !record.supported ->
                Triple("Unsupported", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            record.importStatus == ImportStatus.IMPORTED ->
                Triple("Imported", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            record.importStatus == ImportStatus.DUPLICATE ->
                Triple("Duplicate", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            record.importStatus == ImportStatus.UPDATED ->
                Triple("Updated", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            record.importStatus == ImportStatus.ERROR ->
                Triple("Error", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            else -> return
        }
    Box(
        modifier =
            Modifier
                .padding(top = 2.dp)
                .background(color = container, shape = MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}
