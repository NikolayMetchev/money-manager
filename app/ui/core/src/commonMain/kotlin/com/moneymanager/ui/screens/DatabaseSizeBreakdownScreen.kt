@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.formatGroupedNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jacobras.humanreadable.HumanReadable

private enum class SortDirection { ASC, DESC }

private enum class SizeSortKey { NAME, TYPE, SIZE, PERCENT, PAGES, ROWS, COLUMNS }

/** Maximum number of individual pie slices; remaining objects are aggregated into "Other". */
private const val MAX_PIE_SLICES = 12

/** A distinct, color-blind-friendly-ish palette used for pie slices and their legend swatches. */
private val PIE_PALETTE =
    listOf(
        Color(0xFF4E79A7),
        Color(0xFFF28E2B),
        Color(0xFFE15759),
        Color(0xFF76B7B2),
        Color(0xFF59A14F),
        Color(0xFFEDC948),
        Color(0xFFB07AA1),
        Color(0xFFFF9DA7),
        Color(0xFF9C755F),
        Color(0xFFBAB0AC),
        Color(0xFF86BCB6),
        Color(0xFFD37295),
    )

private val OTHER_COLOR = Color(0xFF8C8C8C)

private data class PieSlice(
    val label: String,
    val bytes: Long,
    val color: Color,
)

@Composable
fun DatabaseSizeBreakdownScreen(
    database: MoneyManagerDatabaseWrapper,
    onBack: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var breakdown by remember(database) { mutableStateOf<List<MoneyManagerDatabaseWrapper.DbObjectSize>>(emptyList()) }
    var isLoading by remember(database) { mutableStateOf(false) }
    var errorMessage by remember(database) { mutableStateOf<String?>(null) }

    suspend fun load() {
        isLoading = true
        errorMessage = null
        breakdown =
            runCatching {
                withContext(Dispatchers.Default) {
                    database.getDbSizeBreakdown()
                }
            }.onFailure { expected ->
                errorMessage = "Failed to load database size breakdown: ${expected.message}"
            }.getOrDefault(emptyList())
        isLoading = false
    }

    LaunchedEffect(database) { load() }

    val totalBytes = remember(breakdown) { breakdown.sumOf { it.totalBytes } }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Database Size Breakdown",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Total: ${HumanReadable.fileSize(totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { scope.launch { load() } },
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading && breakdown.isEmpty() ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            errorMessage != null ->
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

            breakdown.isEmpty() ->
                Text(
                    text = "No breakdown available (dbstat may be unavailable in this SQLite build).",
                    style = MaterialTheme.typography.bodyMedium,
                )

            else -> {
                PieChartWithLegend(
                    breakdown = breakdown,
                    totalBytes = totalBytes,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                SizeBreakdownTable(
                    breakdown = breakdown,
                    totalBytes = totalBytes,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private fun buildPieSlices(breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>): List<PieSlice> {
    // breakdown arrives sorted by size descending; take the largest objects individually.
    val top = breakdown.take(MAX_PIE_SLICES)
    val rest = breakdown.drop(MAX_PIE_SLICES)
    val slices =
        top.mapIndexed { index, item ->
            PieSlice(item.objectName, item.totalBytes, PIE_PALETTE[index % PIE_PALETTE.size])
        }
    return if (rest.isEmpty()) {
        slices
    } else {
        slices + PieSlice("Other (${rest.size})", rest.sumOf { it.totalBytes }, OTHER_COLOR)
    }
}

@Composable
private fun PieChartWithLegend(
    breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val slices = remember(breakdown) { buildPieSlices(breakdown) }

    Row(
        modifier = modifier.height(220.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Canvas(modifier = Modifier.size(200.dp)) {
            if (totalBytes <= 0L) return@Canvas
            var startAngle = -90f
            val diameter = minOf(size.width, size.height)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            slices.forEach { slice ->
                val sweep = (slice.bytes.toFloat() / totalBytes.toFloat()) * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                )
                startAngle += sweep
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slices.forEach { slice ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .clip(RectangleShape)
                                .background(slice.color),
                    )
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatPercentage(slice.bytes, totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SizeBreakdownTable(
    breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    var activeSortKey by remember { mutableStateOf(SizeSortKey.SIZE) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }

    val onHeaderClick: (SizeSortKey) -> Unit = { key ->
        if (activeSortKey == key) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            activeSortKey = key
            // Sizes/percentages/counts read most naturally largest-first; text columns ascending.
            sortDirection =
                if (key == SizeSortKey.NAME || key == SizeSortKey.TYPE) SortDirection.ASC else SortDirection.DESC
        }
    }

    val sortedRows =
        remember(breakdown, activeSortKey, sortDirection) {
            val comparator: Comparator<MoneyManagerDatabaseWrapper.DbObjectSize> =
                when (activeSortKey) {
                    SizeSortKey.NAME -> compareBy { it.objectName.lowercase() }
                    SizeSortKey.TYPE -> compareBy { it.objectType }
                    SizeSortKey.SIZE, SizeSortKey.PERCENT -> compareBy { it.totalBytes }
                    SizeSortKey.PAGES -> compareBy { it.pageCount }
                    SizeSortKey.ROWS -> compareBy { it.rowCount ?: -1L }
                    SizeSortKey.COLUMNS -> compareBy { it.columnCount ?: -1L }
                }
            if (sortDirection == SortDirection.ASC) {
                breakdown.sortedWith(comparator)
            } else {
                breakdown.sortedWith(comparator.reversed())
            }
        }

    val lazyListState = rememberLazyListState()

    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SizeHeaderCell("Object", SizeSortKey.NAME, activeSortKey, sortDirection, onHeaderClick, Modifier.weight(1f))
            SizeHeaderCell("Type", SizeSortKey.TYPE, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
            SizeHeaderCell("Size", SizeSortKey.SIZE, activeSortKey, sortDirection, onHeaderClick, Modifier.width(110.dp))
            SizeHeaderCell("% of total", SizeSortKey.PERCENT, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
            SizeHeaderCell("Pages", SizeSortKey.PAGES, activeSortKey, sortDirection, onHeaderClick, Modifier.width(80.dp))
            SizeHeaderCell("Rows", SizeSortKey.ROWS, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
            SizeHeaderCell("Columns", SizeSortKey.COLUMNS, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = lazyListState) {
                itemsIndexed(sortedRows) { index, item ->
                    val background =
                        if (index % 2 == 0) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(background),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SizeBodyCell(item.objectName, Modifier.weight(1f))
                        SizeBodyCell(item.objectType, Modifier.width(90.dp))
                        SizeBodyCell(HumanReadable.fileSize(item.totalBytes), Modifier.width(110.dp))
                        SizeBodyCell(
                            formatPercentage(item.totalBytes, totalBytes),
                            Modifier.width(90.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        SizeBodyCell(item.pageCount.toString(), Modifier.width(80.dp))
                        SizeBodyCell(item.rowCount?.let { formatGroupedNumber(it) } ?: "—", Modifier.width(90.dp))
                        SizeBodyCell(item.columnCount?.toString() ?: "—", Modifier.width(90.dp))
                    }
                }
            }
            VerticalScrollbarForLazyList(
                lazyListState = lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SizeHeaderCell(
    text: String,
    sortKey: SizeSortKey,
    activeSortKey: SizeSortKey,
    sortDirection: SortDirection,
    onHeaderClick: (SizeSortKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clickable { onHeaderClick(sortKey) }
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline)
                .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (activeSortKey == sortKey) {
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

@Composable
private fun SizeBodyCell(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Box(
        modifier =
            modifier
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                .padding(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatPercentage(
    bytes: Long,
    totalBytes: Long,
): String {
    if (totalBytes <= 0L) return "0.0%"
    val tenths = (bytes * 1000) / totalBytes
    return "${tenths / 10}.${tenths % 10}%"
}
