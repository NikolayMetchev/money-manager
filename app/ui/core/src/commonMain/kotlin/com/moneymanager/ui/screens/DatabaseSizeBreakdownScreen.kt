@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.transactions.ScreenSizeClass
import com.moneymanager.ui.util.formatGroupedNumber
import ir.ehsannarmani.compose_charts.PieChart
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.Pie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jacobras.humanreadable.HumanReadable
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class SortDirection { ASC, DESC }

private enum class SizeSortKey { NAME, TYPE, SIZE, PERCENT, PAGES, ROWS, COLUMNS }

/** Maximum number of individual pie slices; remaining objects are aggregated into "Other". */
private const val MAX_PIE_SLICES = 12

/** Width of the table's leftmost colour-swatch column. */
private val COLOR_COLUMN_WIDTH = 44.dp

/** Fixed width of the frozen "Object" column when the table scrolls horizontally (Compact width). */
private val OBJECT_COLUMN_WIDTH = 150.dp

/** Gap between the pointer and the slice tooltip so the cursor doesn't cover it. */
private val TOOLTIP_OFFSET = 12.dp

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Phones (Compact width) drop the screen title to save vertical space and cap the pie at half
        // the available height so the table below stays reachable; larger windows use a fixed height.
        val compact = ScreenSizeClass.fromWidth(maxWidth) == ScreenSizeClass.Compact
        val chartHeight = if (compact) maxHeight * 0.5f else 280.dp

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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (compact) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Text(
                        text = "Database Size Breakdown",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    DatabaseSizePieChart(
                        breakdown = breakdown,
                        totalBytes = totalBytes,
                        modifier = Modifier.fillMaxWidth().height(chartHeight),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SizeBreakdownTable(
                        breakdown = breakdown,
                        totalBytes = totalBytes,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
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

/**
 * Maps each object name to the pie slice colour it contributes to, so the table's colour column lines
 * up with the chart. The largest [MAX_PIE_SLICES] objects get their own palette colour; everything
 * else falls into the grey "Other" slice (the default returned by lookups that miss this map).
 */
private fun sliceColors(breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>): Map<String, Color> =
    breakdown
        .take(MAX_PIE_SLICES)
        .mapIndexed { index, item -> item.objectName to PIE_PALETTE[index % PIE_PALETTE.size] }
        .toMap()

/**
 * Renders the size breakdown as a pie chart. Slice colours are correlated with the table below via
 * its colour column (see [sliceColors]) rather than an inline legend.
 *
 * The library's [PieChart] has no built-in tooltips, so we overlay our own: an invisible pointer layer
 * over the wedges hit-tests the cursor (desktop hover) or tap (touch) against the slice geometry — see
 * [pieSliceAt] — and shows a [PieTooltip] for the slice underneath. The caller sizes the chart via
 * [modifier] (e.g. a fixed or half-screen height); the pie fills the smaller of the available dimensions.
 */
@Composable
private fun DatabaseSizePieChart(
    breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val slices = remember(breakdown) { buildPieSlices(breakdown) }
    var pieSizePx by remember { mutableStateOf(IntSize.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var activeSlice by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (totalBytes > 0L) {
            val pieSize = minOf(maxWidth, maxHeight)
            Box(
                modifier =
                    Modifier
                        .size(pieSize)
                        .onSizeChanged { pieSizePx = it }
                        .pointerInput(slices, totalBytes) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Move, PointerEventType.Enter -> {
                                            val position = event.changes.first().position
                                            pointer = position
                                            activeSlice = pieSliceAt(position, pieSizePx, slices, totalBytes)
                                        }

                                        PointerEventType.Exit -> activeSlice = null
                                    }
                                }
                            }
                        },
            ) {
                PieChart(
                    modifier = Modifier.fillMaxSize(),
                    data =
                        slices.map { slice ->
                            Pie(
                                label = slice.label,
                                data = slice.bytes.toDouble(),
                                color = slice.color,
                                selectedColor = slice.color,
                            )
                        },
                    onPieClick = { pie ->
                        // Touch devices have no hover; surface the tooltip on tap instead, anchored at
                        // the pie centre since the click position isn't reported by the library.
                        activeSlice = slices.indexOfFirst { it.label == pie.label }.takeIf { it >= 0 }
                        pointer = Offset(pieSizePx.width / 2f, pieSizePx.height / 2f)
                    },
                    style = Pie.Style.Fill,
                    labelHelperProperties = LabelHelperProperties(enabled = false),
                )

                activeSlice?.let { index ->
                    val slice = slices[index]
                    PieTooltip(
                        text = "${slice.label}: ${HumanReadable.fileSize(slice.bytes)} (${formatPercentage(slice.bytes, totalBytes)})",
                        anchor = pointer,
                    )
                }
            }
        }
    }
}

/**
 * Returns the index of the pie slice under [position] within a [size]-pixel square canvas, or null if
 * the pointer is outside the pie. The geometry mirrors the library's [PieChart]: slices start at 0°
 * (3 o'clock) and sweep clockwise with no gaps, radius = min(width, height) / 2, centred on the canvas.
 */
private fun pieSliceAt(
    position: Offset,
    size: IntSize,
    slices: List<PieSlice>,
    totalBytes: Long,
): Int? {
    if (totalBytes <= 0L || size.width == 0 || size.height == 0) return null
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = minOf(size.width, size.height) / 2f
    val dx = position.x - centerX
    val dy = position.y - centerY
    val distanceSquared = dx * dx + dy * dy
    if (distanceSquared > radius * radius) return null
    val magnitude = sqrt(distanceSquared)
    if (magnitude == 0f) return slices.indices.firstOrNull()
    // Clockwise angle from the positive x-axis, matching the library's getAngleInDegree.
    val base = (acos((dx / magnitude).coerceIn(-1f, 1f)) * 180.0 / PI).toFloat()
    val angle = if (dy > 0f) base else 360f - base
    var start = 0f
    for (index in slices.indices) {
        val sweep = (slices[index].bytes.toFloat() / totalBytes.toFloat()) * 360f
        if (angle >= start && angle <= start + sweep) return index
        start += sweep
    }
    return null
}

/** Floating tooltip anchored near the pointer, used to label the hovered/tapped pie slice. */
@Composable
private fun PieTooltip(
    text: String,
    anchor: Offset,
) {
    Surface(
        modifier =
            Modifier.offset {
                IntOffset(
                    x = (anchor.x + TOOLTIP_OFFSET.toPx()).roundToInt(),
                    y = (anchor.y + TOOLTIP_OFFSET.toPx()).roundToInt(),
                )
            },
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SizeBreakdownTable(
    breakdown: List<MoneyManagerDatabaseWrapper.DbObjectSize>,
    totalBytes: Long,
    compact: Boolean,
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

    val colorMap = remember(breakdown) { sliceColors(breakdown) }
    val lazyListState = rememberLazyListState()
    // Shared by the header and every body row so the frozen colour/Object columns stay put while the
    // remaining columns scroll together horizontally (Compact width only; wider layouts fit unscrolled).
    val horizontalScroll = rememberScrollState()

    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SizeColorHeaderCell()
            // Compact freezes Object at a fixed width; wider layouts let it flex to fill the row.
            val objectModifier = if (compact) Modifier.width(OBJECT_COLUMN_WIDTH) else Modifier.weight(1f)
            SizeHeaderCell("Object", SizeSortKey.NAME, activeSortKey, sortDirection, onHeaderClick, objectModifier)
            if (compact) {
                Row(modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                    SizeHeaderTrailingCells(activeSortKey, sortDirection, onHeaderClick)
                }
            } else {
                SizeHeaderTrailingCells(activeSortKey, sortDirection, onHeaderClick)
            }
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
                        SizeColorCell(colorMap[item.objectName] ?: OTHER_COLOR)
                        val objectModifier = if (compact) Modifier.width(OBJECT_COLUMN_WIDTH) else Modifier.weight(1f)
                        SizeBodyCell(item.objectName, objectModifier)
                        if (compact) {
                            Row(modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                                SizeBodyTrailingCells(item, totalBytes)
                            }
                        } else {
                            SizeBodyTrailingCells(item, totalBytes)
                        }
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

/** The data columns after the frozen colour/Object columns, laid out in the enclosing [Row]. */
@Composable
private fun SizeHeaderTrailingCells(
    activeSortKey: SizeSortKey,
    sortDirection: SortDirection,
    onHeaderClick: (SizeSortKey) -> Unit,
) {
    SizeHeaderCell("Type", SizeSortKey.TYPE, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
    SizeHeaderCell("Size", SizeSortKey.SIZE, activeSortKey, sortDirection, onHeaderClick, Modifier.width(110.dp))
    SizeHeaderCell("% of total", SizeSortKey.PERCENT, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
    SizeHeaderCell("Pages", SizeSortKey.PAGES, activeSortKey, sortDirection, onHeaderClick, Modifier.width(80.dp))
    SizeHeaderCell("Rows", SizeSortKey.ROWS, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
    SizeHeaderCell("Columns", SizeSortKey.COLUMNS, activeSortKey, sortDirection, onHeaderClick, Modifier.width(90.dp))
}

/** Body equivalent of [SizeHeaderTrailingCells] for a single [item] row. */
@Composable
private fun SizeBodyTrailingCells(
    item: MoneyManagerDatabaseWrapper.DbObjectSize,
    totalBytes: Long,
) {
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

/** Fixed-width header cell for the (non-sortable) colour column; sized to match the text headers. */
@Composable
private fun SizeColorHeaderCell() {
    Box(
        modifier =
            Modifier
                .width(COLOR_COLUMN_WIDTH)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline)
                .padding(8.dp),
    ) {
        Text(text = " ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** Body cell showing the pie slice colour swatch for a table row. */
@Composable
private fun SizeColorCell(color: Color) {
    Box(
        modifier =
            Modifier
                .width(COLOR_COLUMN_WIDTH)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(14.dp)
                    .clip(RectangleShape)
                    .background(color),
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
