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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.TextStyle
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
import com.moneymanager.domain.model.csv.ImportStatus

private val TRANSFER_COLUMN_WIDTH = 120.dp
private val ROW_INDEX_COLUMN_WIDTH = 60.dp
private val STATUS_COLUMN_WIDTH = 90.dp

private enum class SortDirection { ASC, DESC }

private sealed interface SortKey {
    data object Row : SortKey

    data object Status : SortKey

    data class CsvData(
        val columnIndex: Int,
    ) : SortKey
}

@Composable
fun CsvPreviewTable(
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    modifier: Modifier = Modifier,
    columnWidth: Dp = 150.dp,
    amountColumnIndex: Int? = null,
    failedRowIndexes: Set<Long> = emptySet(),
    scrollToRowIndex: Long? = null,
    onDuplicateSourceClick: ((TransferId, Boolean) -> Unit)? = null,
    onTransferClick: ((TransferId, Boolean) -> Unit)? = null,
) {
    val horizontalScrollState = rememberScrollState()
    val lazyListState = rememberLazyListState()

    val scrolledToRowIndex = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrollToRowIndex, rows.size) {
        val targetRowIndex = scrollToRowIndex ?: return@LaunchedEffect
        if (scrolledToRowIndex.value == targetRowIndex) return@LaunchedEffect
        val targetIndex = rows.indexOfFirst { it.rowIndex == targetRowIndex }
        if (targetIndex >= 0) {
            lazyListState.animateScrollToItem(targetIndex)
            scrolledToRowIndex.value = targetRowIndex
        }
    }

    var activeSortKey by remember { mutableStateOf<SortKey?>(null) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASC) }

    val onHeaderClick: (SortKey) -> Unit =
        remember {
            { key ->
                when {
                    activeSortKey != key -> {
                        activeSortKey = key
                        sortDirection = SortDirection.ASC
                    }
                    sortDirection == SortDirection.ASC -> sortDirection = SortDirection.DESC
                    else -> activeSortKey = null
                }
            }
        }

    val sortedColumns = remember(columns) { columns.sortedBy { it.columnIndex } }

    val sortedRows =
        remember(rows, activeSortKey, sortDirection) {
            val key = activeSortKey ?: return@remember rows
            val comparator: Comparator<CsvRow> =
                when (key) {
                    SortKey.Row -> compareBy { it.rowIndex }
                    SortKey.Status -> compareBy { it.importStatus?.ordinal ?: -1 }
                    is SortKey.CsvData -> compareBy { it.values.getOrElse(key.columnIndex) { "" } }
                }
            if (sortDirection == SortDirection.ASC) {
                rows.sortedWith(comparator)
            } else {
                rows.sortedWith(comparator.reversed())
            }
        }

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
            SortableColumnHeader(
                text = "Row",
                sortKey = SortKey.Row,
                activeSortKey = activeSortKey,
                sortDirection = sortDirection,
                onHeaderClick = onHeaderClick,
                width = ROW_INDEX_COLUMN_WIDTH,
            )

            // Status column header
            SortableColumnHeader(
                text = "Status",
                sortKey = SortKey.Status,
                activeSortKey = activeSortKey,
                sortDirection = sortDirection,
                onHeaderClick = onHeaderClick,
                width = STATUS_COLUMN_WIDTH,
            )

            // Transaction navigation column header (not sortable)
            Box(
                modifier =
                    Modifier
                        .width(TRANSFER_COLUMN_WIDTH)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                        ).padding(8.dp),
            ) {
                SelectableText(
                    text = "Transaction",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // CSV columns
            sortedColumns.forEach { column ->
                SortableColumnHeader(
                    text = column.originalName,
                    sortKey = SortKey.CsvData(column.columnIndex),
                    activeSortKey = activeSortKey,
                    sortDirection = sortDirection,
                    onHeaderClick = onHeaderClick,
                    width = columnWidth,
                )
            }
        }

        // Data rows with vertical scrollbar
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = lazyListState) {
                itemsIndexed(sortedRows) { _, row ->
                    val isFailed = row.rowIndex in failedRowIndexes
                    val hasError = row.errorMessage != null
                    val rowBackground =
                        when {
                            isFailed || hasError -> MaterialTheme.colorScheme.errorContainer
                            row.rowIndex == scrollToRowIndex -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }

                    Column {
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
                                        ).padding(8.dp),
                            ) {
                                SelectableText(
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

                            // Status cell
                            Box(
                                modifier =
                                    Modifier
                                        .width(STATUS_COLUMN_WIDTH)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        ).padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                row.importStatus?.let { status ->
                                    val (statusText, statusColor) =
                                        when (status) {
                                            ImportStatus.IMPORTED -> "New" to MaterialTheme.colorScheme.primary
                                            ImportStatus.DUPLICATE -> "Duplicate" to MaterialTheme.colorScheme.secondary
                                            ImportStatus.UPDATED -> "Updated" to MaterialTheme.colorScheme.tertiary
                                            ImportStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
                                        }
                                    SelectableText(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            val isPositiveAmount =
                                isRowAmountPositive(
                                    row = row,
                                    amountColumnIndex = amountColumnIndex,
                                )

                            // Transfer ID cell
                            TransferIdCell(
                                importStatus = row.importStatus,
                                transferId = row.transferId,
                                isPositiveAmount = isPositiveAmount,
                                onDuplicateSourceClick = onDuplicateSourceClick,
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
                                                ).padding(8.dp),
                                    ) {
                                        SelectableText(
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

                        // Error message row (shown below data row when there's an error)
                        row.errorMessage?.let { error ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                SelectableText(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium,
                                )
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
    importStatus: ImportStatus?,
    transferId: TransferId?,
    isPositiveAmount: Boolean,
    onDuplicateSourceClick: ((TransferId, Boolean) -> Unit)?,
    onClick: ((TransferId, Boolean) -> Unit)?,
) {
    val isDuplicate = importStatus == ImportStatus.DUPLICATE
    val clickAction =
        when {
            transferId == null -> null
            isDuplicate && onDuplicateSourceClick != null -> (
                {
                    onDuplicateSourceClick(transferId, isPositiveAmount)
                }
            )
            isDuplicate -> null
            onClick != null -> ({ onClick(transferId, isPositiveAmount) })
            else -> null
        }

    Box(
        modifier =
            Modifier
                .width(TRANSFER_COLUMN_WIDTH)
                .then(
                    if (clickAction != null) {
                        Modifier.clickable { clickAction() }
                    } else {
                        Modifier
                    },
                ).border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ).padding(8.dp),
    ) {
        if (transferId != null) {
            Text(
                text = if (isDuplicate && onDuplicateSourceClick != null) "Source ->" else "View ->",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = if (clickAction != null) TextDecoration.Underline else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun isRowAmountPositive(
    row: CsvRow,
    amountColumnIndex: Int?,
): Boolean =
    amountColumnIndex?.let { index ->
        val amount = row.values.getOrNull(index)?.trim()
        amount == null || (!amount.startsWith("-") && !amount.startsWith("("))
    } ?: true

@Composable
private fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    SelectionContainer {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            fontWeight = fontWeight,
            textDecoration = textDecoration,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Composable
private fun SortableColumnHeader(
    text: String,
    sortKey: SortKey,
    activeSortKey: SortKey?,
    sortDirection: SortDirection,
    onHeaderClick: (SortKey) -> Unit,
    width: Dp,
) {
    val isActive = activeSortKey == sortKey
    Box(
        modifier =
            Modifier
                .width(width)
                .clickable { onHeaderClick(sortKey) }
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                ).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectableText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Icon(
                    imageVector =
                        if (sortDirection == SortDirection.ASC) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                    contentDescription =
                        if (sortDirection == SortDirection.ASC) "$text sorted ascending" else "$text sorted descending",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
