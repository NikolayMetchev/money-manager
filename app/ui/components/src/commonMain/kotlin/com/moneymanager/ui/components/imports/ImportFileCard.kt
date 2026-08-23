package com.moneymanager.ui.components.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.ui.util.displayDate
import com.moneymanager.ui.util.displayDateTime
import kotlin.time.Instant

/**
 * The card shown for one staged import file, shared by every import format's list screen.
 *
 * Whatever a format renders differently goes through the [details] slot (drawn between the metadata
 * row and the error line) and the [footer] slot (drawn last); both receive the card's metadata
 * colour so extra lines match the rest of the card.
 */
@Suppress("LongParameterList")
@Composable
fun ImportFileCard(
    fileName: String,
    metadataText: String,
    addedAt: Instant,
    errorCount: Int,
    lastAppliedAt: Instant?,
    applicationCount: Int,
    lastAppliedStrategyName: String?,
    dateRange: ImportFileDateRange?,
    ignored: Boolean,
    onClick: () -> Unit,
    onSetIgnored: (Boolean) -> Unit,
    details: @Composable ColumnScope.(Color) -> Unit = {},
    footer: @Composable ColumnScope.(Color) -> Unit = {},
) {
    val isImported = lastAppliedAt != null
    val containerColor =
        if (isImported) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        }
    val metadataColor =
        if (isImported) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!ignored) {
                        ImportStateBadge(isImported = isImported)
                    }
                    // Only unimported files can be ignored; the Ignored tab offers Restore.
                    if (ignored) {
                        TextButton(onClick = { onSetIgnored(false) }) { Text("Restore") }
                    } else if (!isImported) {
                        TextButton(onClick = { onSetIgnored(true) }) { Text("Ignore") }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
                Text(
                    text = "Added ${addedAt.displayDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
            details(metadataColor)
            if (errorCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$errorCount error${if (errorCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    if (lastAppliedAt != null) {
                        buildString {
                            append(if (applicationCount > 1) "Latest import on " else "Imported on ")
                            append(lastAppliedAt.displayDateTime())
                            lastAppliedStrategyName?.takeIf(String::isNotBlank)?.let { strategyName ->
                                append(" via ")
                                append(strategyName)
                            }
                        }
                    } else {
                        "Not imported yet"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (isImported) metadataColor else MaterialTheme.colorScheme.secondary,
            )
            if (dateRange != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text =
                        "Transactions ${dateRange.earliest.displayDate()} → ${dateRange.latest.displayDate()} " +
                            "(${dateRange.transactionCount})",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
            footer(metadataColor)
        }
    }
}

/** A detail line inside an [ImportFileCard] slot, spaced and styled like the card's own lines. */
@Composable
fun ImportCardDetailText(
    text: String,
    color: Color,
    spacing: Dp = 4.dp,
) {
    Spacer(modifier = Modifier.height(spacing))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun ImportStateBadge(isImported: Boolean) {
    val containerColor =
        if (isImported) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (isImported) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Box(
        modifier =
            Modifier
                .background(color = containerColor, shape = MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isImported) "Imported" else "Unimported",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
