package com.moneymanager.ui.components.csv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow

@Composable
fun CsvPreviewTable(
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    modifier: Modifier = Modifier,
    columnWidth: Dp = 150.dp,
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
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontalScrollState),
                    ) {
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
