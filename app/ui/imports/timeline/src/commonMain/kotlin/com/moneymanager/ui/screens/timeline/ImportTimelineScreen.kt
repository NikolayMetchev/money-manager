@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.displayDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock

private val ROW_LABEL_WIDTH = 200.dp
private val ROW_BAR_HEIGHT = 24.dp
private val TOOLTIP_OFFSET = 12.dp

/**
 * Matrix of import coverage: one row per import strategy or per account (toggled by the tabs at
 * the top), sharing a time axis spanning the earliest to the latest imported transaction. Gaps in
 * a row are periods no file covers; darker stretches are covered by more than one file. Hovering a
 * row (desktop) or tapping it (touch) lists the files covering that day; clicking a covered spot
 * opens the file (with a chooser when several files overlap). A table of every gap sits under the
 * timeline.
 */
@Composable
fun ImportTimelineScreen(
    importTimelineRepository: ImportTimelineReadRepository,
    accountRepository: AccountReadRepository,
    personRepository: PersonReadRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    onOpenFile: (ImportFileDateRange) -> Unit = {},
) {
    var groupMode by remember { mutableStateOf(TimelineGroupMode.STRATEGY) }
    var accountNameFilter by remember { mutableStateOf("") }
    var selectedOwnerIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val strategyRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        importTimelineRepository.getAllDateRanges()
    }
    val accountRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        importTimelineRepository.getAllAccountRanges()
    }
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }
    val people by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getAllPeople()
    }
    val ownerships by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personAccountOwnershipRepository.getAllOwnerships()
    }
    val accountNameById = remember(accounts) { accounts.associate { it.id to it.name } }
    val ownerIdsByAccount =
        remember(ownerships) {
            ownerships.groupBy({ it.accountId }, { it.personId.id }).mapValues { it.value.toSet() }
        }

    val timeZone = remember { TimeZone.currentSystemDefault() }
    val matrix =
        remember(groupMode, strategyRanges, accountRanges, accountNameById, accountNameFilter, selectedOwnerIds, ownerIdsByAccount) {
            val todayDay =
                Clock.System
                    .now()
                    .toLocalDateTime(timeZone)
                    .date
                    .toEpochDays()
            when (groupMode) {
                TimelineGroupMode.STRATEGY -> buildTimelineMatrix(strategyRanges, timeZone, todayDay, groupMode)
                TimelineGroupMode.ACCOUNT -> {
                    val filtered =
                        filterAccountRanges(
                            accountRanges,
                            accountNameById,
                            accountNameFilter,
                            selectedOwnerIds,
                            ownerIdsByAccount,
                        )
                    buildTimelineMatrix(filtered, timeZone, todayDay, groupMode, accountNameById)
                }
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = groupMode.ordinal) {
            Tab(
                selected = groupMode == TimelineGroupMode.STRATEGY,
                onClick = { groupMode = TimelineGroupMode.STRATEGY },
                text = { Text("By strategy") },
            )
            Tab(
                selected = groupMode == TimelineGroupMode.ACCOUNT,
                onClick = { groupMode = TimelineGroupMode.ACCOUNT },
                text = { Text("By account") },
            )
        }

        if (groupMode == TimelineGroupMode.ACCOUNT) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = accountNameFilter,
                    onValueChange = { accountNameFilter = it },
                    label = { Text("Search accounts") },
                    placeholder = { Text("Type to search...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (people.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OwnerFilterDropdown(
                        people = people,
                        selectedOwnerIds = selectedOwnerIds,
                        onSelectionChange = { selectedOwnerIds = it },
                    )
                }
            }
        }

        if (matrix == null) {
            val filtersActive = groupMode == TimelineGroupMode.ACCOUNT && (accountNameFilter.isNotBlank() || selectedOwnerIds.isNotEmpty())
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (filtersActive) "No accounts match the current filters." else "No imported transactions yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            TimelineLegend()
            Spacer(modifier = Modifier.height(12.dp))
            matrix.rows.forEach { row ->
                TimelineRowItem(row = row, minDay = matrix.minDay, maxDay = matrix.maxDay, onOpenFile = onOpenFile)
                Spacer(modifier = Modifier.height(8.dp))
            }
            TimelineAxis(minDay = matrix.minDay, maxDay = matrix.maxDay)
            Spacer(modifier = Modifier.height(24.dp))
            GapsTable(matrix, groupMode)
        }
    }
}

@Composable
private fun GapsTable(
    matrix: TimelineMatrix,
    groupMode: TimelineGroupMode,
) {
    val gaps = matrix.rows.flatMap { row -> row.gaps.map { gap -> row.label to gap } }
    val columnLabel = if (groupMode == TimelineGroupMode.ACCOUNT) "Account" else "Strategy"
    Text(text = "Gaps", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    if (gaps.isEmpty()) {
        Text(
            text = "No gaps — every ${columnLabel.lowercase()} is fully covered up to today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    GapsTableRow(strategy = columnLabel, from = "From", to = "To", days = "Days", header = true)
    gaps.forEach { (label, gap) ->
        GapsTableRow(
            strategy = label,
            from = LocalDate.fromEpochDays(gap.startDay).toString(),
            to = LocalDate.fromEpochDays(gap.endDay).toString(),
            days = (gap.endDay - gap.startDay + 1).toString(),
        )
    }
}

@Composable
private fun GapsTableRow(
    strategy: String,
    from: String,
    to: String,
    days: String,
    header: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = strategy, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(ROW_LABEL_WIDTH))
        Text(text = from, style = style, modifier = Modifier.width(110.dp))
        Text(text = to, style = style, modifier = Modifier.width(110.dp))
        Text(text = days, style = style, modifier = Modifier.width(60.dp))
    }
}

@Composable
private fun TimelineLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendSwatch(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), label = "Covered")
        Spacer(modifier = Modifier.width(16.dp))
        LegendSwatch(color = MaterialTheme.colorScheme.primary, label = "Overlapping files")
        Spacer(modifier = Modifier.width(16.dp))
        LegendSwatch(color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f), label = "Gap")
    }
}

@Composable
private fun LegendSwatch(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TimelineRowItem(
    row: TimelineRow,
    minDay: Long,
    maxDay: Long,
    onOpenFile: (ImportFileDateRange) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(ROW_LABEL_WIDTH)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val fileCount = row.files.size
            Text(
                text = if (fileCount == 1) "1 file" else "$fileCount files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TimelineRowBar(
            row = row,
            minDay = minDay,
            maxDay = maxDay,
            onOpenFile = onOpenFile,
            modifier = Modifier.weight(1f).height(ROW_BAR_HEIGHT),
        )
    }
}

@Composable
private fun TimelineRowBar(
    row: TimelineRow,
    minDay: Long,
    maxDay: Long,
    onOpenFile: (ImportFileDateRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spanDays = (maxDay - minDay + 1).coerceAtLeast(1)
    var barSizePx by remember { mutableStateOf(IntSize.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var hovered by remember(row) { mutableStateOf<TimelineHover?>(null) }
    var chooser by remember(row) { mutableStateOf<List<TimelineFile>>(emptyList()) }
    var chooserAt by remember { mutableStateOf(Offset.Zero) }

    fun dayAt(x: Float): Long = minDay + ((x / barSizePx.width) * spanDays).toLong().coerceIn(0, spanDays - 1)

    fun hoverAt(x: Float): TimelineHover? {
        if (barSizePx.width <= 0) return null
        val day = dayAt(x)
        val files = filesAt(row, day)
        if (files.isNotEmpty()) return TimelineHover.Files(files)
        return gapAt(row, day)?.let { TimelineHover.Gap(it) }
    }

    val baseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val overlapColor = MaterialTheme.colorScheme.primary
    val gapColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
                .onSizeChanged { barSizePx = it }
                .pointerInput(row, minDay, maxDay) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Move, PointerEventType.Enter, PointerEventType.Press -> {
                                    val position = event.changes.first().position
                                    pointer = position
                                    hovered = hoverAt(position.x)
                                }
                                PointerEventType.Release -> {
                                    val position = event.changes.first().position
                                    if (barSizePx.width > 0) {
                                        val clickable =
                                            filesAt(row, dayAt(position.x))
                                                .filter { it.kind != TimelineSourceKind.MANUAL }
                                        when {
                                            clickable.size == 1 -> onOpenFile(clickable.single().range)
                                            clickable.size > 1 -> {
                                                chooserAt = position
                                                chooser = clickable
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Exit -> hovered = null
                            }
                        }
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pxPerDay = size.width / spanDays

            fun drawSpan(
                startDay: Long,
                endDay: Long,
                color: Color,
            ) {
                val left = (startDay - minDay) * pxPerDay
                val right = (endDay - minDay + 1) * pxPerDay
                drawRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size =
                        Size(
                            width = (right - left).coerceAtLeast(2f),
                            height = size.height,
                        ),
                )
            }
            row.gaps.forEach { gap -> drawSpan(gap.startDay, gap.endDay, gapColor) }
            row.segments.forEach { segment ->
                drawSpan(segment.startDay, segment.endDay, if (segment.depth >= 2) overlapColor else baseColor)
            }
        }
        hovered?.let { TimelineTooltip(hover = it, anchor = pointer) }
        if (chooser.isNotEmpty()) {
            val density = LocalDensity.current
            DropdownMenu(
                expanded = true,
                onDismissRequest = { chooser = emptyList() },
                offset =
                    with(density) {
                        DpOffset(x = chooserAt.x.toDp(), y = chooserAt.y.toDp() - ROW_BAR_HEIGHT)
                    },
            ) {
                chooser.forEach { file ->
                    DropdownMenuItem(
                        text = { Text(file.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            chooser = emptyList()
                            onOpenFile(file.range)
                        },
                    )
                }
            }
        }
    }
}

/** What the cursor is over inside a timeline row: covered files, or a gap between them. */
private sealed interface TimelineHover {
    data class Files(
        val files: List<TimelineFile>,
    ) : TimelineHover

    data class Gap(
        val gap: TimelineGap,
    ) : TimelineHover
}

/**
 * Floating tooltip for the hovered files or gap. Rendered in a [Popup] so it escapes the
 * clipped 24dp bar and draws above the neighbouring rows.
 */
@Composable
private fun TimelineTooltip(
    hover: TimelineHover,
    anchor: Offset,
) {
    val offsetPx = with(LocalDensity.current) { TOOLTIP_OFFSET.roundToPx() }
    Popup(
        offset =
            IntOffset(
                x = anchor.x.roundToInt() + offsetPx,
                y = anchor.y.roundToInt() + offsetPx,
            ),
    ) {
        TimelineTooltipContent(hover)
    }
}

@Composable
private fun TimelineTooltipContent(hover: TimelineHover) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            when (hover) {
                is TimelineHover.Files ->
                    hover.files.forEach { file ->
                        val range = file.range
                        Text(
                            text =
                                "${file.label} — ${range.earliest.displayDate()} → ${range.latest.displayDate()} " +
                                    "(${range.transactionCount} tx)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                is TimelineHover.Gap -> {
                    val gap = hover.gap
                    val days = gap.endDay - gap.startDay + 1
                    Text(
                        text =
                            "Gap: ${LocalDate.fromEpochDays(gap.startDay)} → ${LocalDate.fromEpochDays(gap.endDay)} " +
                                "($days day${if (days == 1L) "" else "s"})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineAxis(
    minDay: Long,
    maxDay: Long,
) {
    val ticks = remember(minDay, maxDay) { axisTicks(minDay, maxDay) }
    val spanDays = (maxDay - minDay + 1).coerceAtLeast(1)
    Row {
        Spacer(modifier = Modifier.width(ROW_LABEL_WIDTH))
        BoxWithConstraints(modifier = Modifier.weight(1f).height(24.dp)) {
            val widthPx = constraints.maxWidth
            ticks.forEach { (day, label) ->
                val fraction = (day - minDay).toFloat() / spanDays
                Column(
                    modifier = Modifier.offset { IntOffset(x = (fraction * widthPx).roundToInt(), y = 0) },
                    horizontalAlignment = Alignment.Start,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(1.dp)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Multi-select owner filter, matching the one on the Accounts screen. */
@Composable
private fun OwnerFilterDropdown(
    people: List<Person>,
    selectedOwnerIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectionLabel =
        when (selectedOwnerIds.size) {
            0 -> "All owners"
            1 -> people.find { it.id.id in selectedOwnerIds }?.fullName ?: "1 owner"
            else -> "${selectedOwnerIds.size} owners"
        }

    val filteredPeople =
        remember(people, searchQuery) {
            if (searchQuery.isBlank()) people else people.filter { it.fullName.contains(searchQuery, ignoreCase = true) }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = if (expanded) searchQuery else selectionLabel,
            onValueChange = { searchQuery = it },
            label = { Text("Filter by owner") },
            placeholder = { Text("Type to search...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            DropdownMenuItem(
                text = { Text("All owners") },
                onClick = { onSelectionChange(emptySet()) },
            )
            filteredPeople.forEach { person ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedOwnerIds.contains(person.id.id),
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(person.fullName)
                        }
                    },
                    onClick = {
                        onSelectionChange(
                            if (selectedOwnerIds.contains(person.id.id)) {
                                selectedOwnerIds - person.id.id
                            } else {
                                selectedOwnerIds + person.id.id
                            },
                        )
                    },
                )
            }
        }
    }
}
