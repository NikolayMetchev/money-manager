package com.moneymanager.ui.components.csv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow

private val TRANSFER_COLUMN_WIDTH = 100.dp
private val ROW_INDEX_COLUMN_WIDTH = 60.dp

@Composable
fun CsvPreviewTable(
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    modifier: Modifier = Modifier,
    columnWidth: Dp = 150.dp,
    amountColumnIndex: Int? = null,
    failedRowIndexes: Set<Long> = emptySet(),
    onTransferClick: ((TransferId, Boolean) -> Unit)? = null,
) {
    val horizontalScrollState = rememberScrollState()
    val lazyListState = rememberLazyListState()

    Column(modifier = modifier) {
        // Header row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Row index column header
            Box(
                modifier =
                    Modifier
                        .width(ROW_INDEX_COLUMN_WIDTH)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        .padding(8.dp),
            ) {
                Text(
                    text = "Row",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Transfer ID column header
            Box(
                modifier =
                    Modifier
                        .width(TRANSFER_COLUMN_WIDTH)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        .padding(8.dp),
            ) {
                Text(
                    text = "Transaction Id",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // CSV columns
            columns.sortedBy { it.columnIndex }.forEach { column ->
                Box(
                    modifier =
                        Modifier
                            .width(columnWidth)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            .padding(8.dp),
                ) {
                    Text(
                        text = column.originalName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Data rows with vertical scrollbar
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = lazyListState) {
                itemsIndexed(rows) { _, row ->
                    val isFailed = row.rowIndex in failedRowIndexes
                    val rowBackground =
                        if (isFailed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontalScrollState)
                                .background(rowBackground),
                    ) {
                        // Row index cell
                        Box(
                            modifier =
                                Modifier
                                    .width(ROW_INDEX_COLUMN_WIDTH)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    .padding(8.dp),
                        ) {
                            Text(
                                text = row.rowIndex.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isFailed) FontWeight.Bold else FontWeight.Normal,
                                color =
                                    if (isFailed) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Determine if amount is positive from the amount column
                        val isPositiveAmount =
                            amountColumnIndex?.let { idx ->
                                if (idx in row.values.indices) {
                                    val amountStr = row.values[idx].trim()
                                    // Parse the amount string - positive if it doesn't start with '-'
                                    // Also handle parentheses notation (123) as negative
                                    !amountStr.startsWith("-") && !amountStr.startsWith("(")
                                } else {
                                    true
                                }
                            } ?: true

                        // Transfer ID cell
                        TransferIdCell(
                            transferId = row.transferId,
                            isPositiveAmount = isPositiveAmount,
                            onClick = onTransferClick,
                        )

                        // CSV data cells
                        row.values.forEachIndexed { index, value ->
                            if (index < columns.size) {
                                Box(
                                    modifier =
                                        Modifier
                                            .width(columnWidth)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                            .padding(8.dp),
                                ) {
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (isFailed) {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            VerticalScrollbarForLazyList(
                lazyListState = lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }

        // Horizontal scrollbar at the bottom
        HorizontalScrollbarForScrollState(
            scrollState = horizontalScrollState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransferIdCell(
    transferId: TransferId?,
    isPositiveAmount: Boolean,
    onClick: ((TransferId, Boolean) -> Unit)?,
) {
    Box(
        modifier =
            Modifier
                .width(TRANSFER_COLUMN_WIDTH)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                .then(
                    if (transferId != null && onClick != null) {
                        Modifier.clickable { onClick(transferId, isPositiveAmount) }
                    } else {
                        Modifier
                    },
                )
                .padding(8.dp),
    ) {
        if (transferId != null) {
            Text(
                text = "View →",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = if (onClick != null) TextDecoration.Underline else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
