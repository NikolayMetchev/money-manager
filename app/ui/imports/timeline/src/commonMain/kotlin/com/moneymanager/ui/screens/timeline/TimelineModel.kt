@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.timeline

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Which dimension timeline rows are grouped by. */
enum class TimelineGroupMode {
    STRATEGY,
    ACCOUNT,
}

/**
 * Narrows account-grouped ranges (see [TimelineGroupMode.ACCOUNT]) to those whose account matches
 * both [nameFilter] (case-insensitive substring of the account name) and [selectedOwnerIds] (a
 * range's account must have at least one of the selected owners; an empty selection matches
 * everything) — the same two filters as the Accounts screen. A range whose account is unknown
 * (missing from [accountNameById]) never matches a non-blank [nameFilter] or a non-empty owner
 * selection.
 */
fun filterAccountRanges(
    ranges: List<ImportFileDateRange>,
    accountNameById: Map<AccountId, String>,
    nameFilter: String,
    selectedOwnerIds: Set<Long>,
    ownerIdsByAccount: Map<AccountId, Set<Long>>,
): List<ImportFileDateRange> =
    ranges.filter { range ->
        val accountId = range.accountId
        val matchesName =
            nameFilter.isBlank() ||
                (accountId != null && accountNameById[accountId]?.contains(nameFilter, ignoreCase = true) == true)
        val matchesOwner =
            selectedOwnerIds.isEmpty() ||
                (accountId != null && ownerIdsByAccount[accountId].orEmpty().any { it in selectedOwnerIds })
        matchesName && matchesOwner
    }

/** One file/session bar inside a timeline row, in local epoch days (inclusive on both ends). */
data class TimelineFile(
    val label: String,
    val kind: TimelineSourceKind,
    val startDay: Long,
    val endDay: Long,
    val range: ImportFileDateRange,
)

/** A stretch of days covered by [depth] files; depth >= 2 marks overlapping coverage. */
data class TimelineSegment(
    val startDay: Long,
    val endDay: Long,
    val depth: Int,
)

/** An uncovered stretch of days between a row's covered segments (both ends inclusive). */
data class TimelineGap(
    val startDay: Long,
    val endDay: Long,
)

data class TimelineRow(
    val label: String,
    val files: List<TimelineFile>,
    val segments: List<TimelineSegment>,
    val gaps: List<TimelineGap>,
)

data class TimelineMatrix(
    val minDay: Long,
    val maxDay: Long,
    val rows: List<TimelineRow>,
)

private const val MANUAL_ROW_LABEL = "Manual entries"
private const val UNKNOWN_STRATEGY_LABEL = "Unknown strategy"
private const val UNKNOWN_ACCOUNT_LABEL = "Unknown account"

/**
 * Groups per-file date ranges into timeline rows keyed by strategy name ([TimelineGroupMode.STRATEGY])
 * or by account name ([TimelineGroupMode.ACCOUNT]).
 *
 * In [TimelineGroupMode.STRATEGY]: CSV and QIF files that share a strategy land in the same row
 * (QIF reuses CSV strategies — one strategy is one logical source, and a CSV+QIF export of the
 * same period should show up as overlap, not as two rows). Strategy names are per-application
 * snapshots, so files imported before and after a strategy rename split into separate rows. API
 * sessions group by API strategy name, falling back to the session type for legacy strategy-less
 * sessions. The manual aggregate gets its own last row.
 *
 * In [TimelineGroupMode.ACCOUNT]: every transfer touches two accounts, so [ranges] is expected to
 * already be the account-unpivoted form (one entry per file/session/manual *and* touched account —
 * see `ImportTimelineReadRepository.getAllAccountRanges`), and rows are keyed by [accountNameById]
 * looked up from `range.accountId`. A file whose transfers touch several accounts naturally lands
 * in several rows, one per account.
 *
 * The axis spans the earliest transaction anywhere up to [todayDay]. A row whose coverage starts
 * later than the axis gets a leading gap, and one whose coverage stops before today gets a
 * trailing gap, so missing data at either end is as visible as holes in the middle.
 */
fun buildTimelineMatrix(
    ranges: List<ImportFileDateRange>,
    timeZone: TimeZone,
    todayDay: Long,
    groupMode: TimelineGroupMode = TimelineGroupMode.STRATEGY,
    accountNameById: Map<AccountId, String> = emptyMap(),
): TimelineMatrix? {
    if (ranges.isEmpty()) return null
    val files =
        ranges.map { range ->
            TimelineFile(
                label = fileLabel(range, timeZone),
                kind = range.kind,
                startDay = range.earliest.toEpochDay(timeZone),
                endDay = range.latest.toEpochDay(timeZone),
                range = range,
            )
        }
    val minDay = files.minOf { it.startDay }
    val maxDay = maxOf(files.maxOf { it.endDay }, todayDay)
    val rows =
        files
            .groupBy { rowLabel(it.range, groupMode, accountNameById) }
            .map { (label, rowFiles) ->
                val segments = mergeIntervals(rowFiles)
                TimelineRow(
                    label = label,
                    files = rowFiles.sortedBy { it.startDay },
                    segments = segments,
                    gaps = leadingGap(segments, minDay) + gapsBetween(segments) + trailingGap(segments, maxDay),
                )
            }.sortedWith(
                compareBy(
                    { groupMode == TimelineGroupMode.STRATEGY && it.files.first().kind == TimelineSourceKind.MANUAL },
                    { it.label.lowercase() },
                ),
            )
    return TimelineMatrix(
        minDay = minDay,
        maxDay = maxDay,
        rows = rows,
    )
}

/** A gap from the start of the axis up to the day before the row's first coverage. */
private fun leadingGap(
    segments: List<TimelineSegment>,
    axisStartDay: Long,
): List<TimelineGap> {
    val firstCoveredDay = segments.firstOrNull()?.startDay ?: return emptyList()
    return if (firstCoveredDay > axisStartDay) {
        listOf(TimelineGap(startDay = axisStartDay, endDay = firstCoveredDay - 1))
    } else {
        emptyList()
    }
}

/** A gap from the day after the row's last coverage up to the end of the axis (today). */
private fun trailingGap(
    segments: List<TimelineSegment>,
    axisEndDay: Long,
): List<TimelineGap> {
    val lastCoveredDay = segments.lastOrNull()?.endDay ?: return emptyList()
    return if (lastCoveredDay < axisEndDay) {
        listOf(TimelineGap(startDay = lastCoveredDay + 1, endDay = axisEndDay))
    } else {
        emptyList()
    }
}

private fun rowLabel(
    range: ImportFileDateRange,
    groupMode: TimelineGroupMode,
    accountNameById: Map<AccountId, String>,
): String =
    when (groupMode) {
        TimelineGroupMode.ACCOUNT -> accountNameById[range.accountId] ?: UNKNOWN_ACCOUNT_LABEL
        TimelineGroupMode.STRATEGY ->
            when (range.kind) {
                TimelineSourceKind.MANUAL -> MANUAL_ROW_LABEL
                TimelineSourceKind.API -> range.strategyName ?: UNKNOWN_STRATEGY_LABEL
                TimelineSourceKind.CSV, TimelineSourceKind.QIF -> range.strategyName ?: UNKNOWN_STRATEGY_LABEL
            }
    }

private fun fileLabel(
    range: ImportFileDateRange,
    timeZone: TimeZone,
): String =
    when (range.kind) {
        TimelineSourceKind.MANUAL -> range.fileName
        TimelineSourceKind.API -> "[API] ${range.fileName} (${range.earliest.toLocalDate(timeZone)})"
        TimelineSourceKind.CSV -> "[CSV] ${range.fileName}" + if (range.ignored) " (ignored)" else ""
        TimelineSourceKind.QIF -> "[QIF] ${range.fileName}" + if (range.ignored) " (ignored)" else ""
    }

/**
 * Sweep-line merge of the row's file intervals into contiguous covered segments annotated with
 * coverage depth. Boundaries are +1/-1 events at startDay and endDay + 1; a stretch with depth 0
 * is a gap and produces no segment.
 */
fun mergeIntervals(files: List<TimelineFile>): List<TimelineSegment> {
    val deltas = mutableMapOf<Long, Int>()
    for (file in files) {
        deltas[file.startDay] = (deltas[file.startDay] ?: 0) + 1
        deltas[file.endDay + 1] = (deltas[file.endDay + 1] ?: 0) - 1
    }
    val segments = mutableListOf<TimelineSegment>()
    var depth = 0
    var previousDay = 0L
    for ((day, delta) in deltas.entries.sortedBy { it.key }) {
        if (depth > 0 && previousDay < day) {
            segments += TimelineSegment(previousDay, day - 1, depth)
        }
        depth += delta
        previousDay = day
    }
    return segments
}

/**
 * The uncovered stretches between consecutive covered segments. Days before the first and after
 * the last segment are not gaps — the source simply doesn't span them.
 */
fun gapsBetween(segments: List<TimelineSegment>): List<TimelineGap> =
    segments.zipWithNext().mapNotNull { (previous, next) ->
        if (previous.endDay + 1 < next.startDay) {
            TimelineGap(startDay = previous.endDay + 1, endDay = next.startDay - 1)
        } else {
            null
        }
    }

/** Files whose range covers the given local epoch day — the hover tooltip's content. */
fun filesAt(
    row: TimelineRow,
    day: Long,
): List<TimelineFile> = row.files.filter { day in it.startDay..it.endDay }

/** The gap containing the given local epoch day, if the cursor is over one. */
fun gapAt(
    row: TimelineRow,
    day: Long,
): TimelineGap? = row.gaps.firstOrNull { day in it.startDay..it.endDay }

private const val YEAR_TICKS_MIN_SPAN_DAYS = 4 * 365L
private const val QUARTER_TICKS_MIN_SPAN_DAYS = 365L
private const val MONTHS_PER_QUARTER = 3

/**
 * Axis tick positions (local epoch day) with labels, adapted to the span: January firsts labelled
 * with the year for long spans, month firsts (every month or every quarter) for shorter ones.
 */
fun axisTicks(
    minDay: Long,
    maxDay: Long,
): List<Pair<Long, String>> {
    val span = maxDay - minDay
    val start = LocalDate.fromEpochDays(minDay)
    val ticks = mutableListOf<Pair<Long, String>>()
    when {
        span >= YEAR_TICKS_MIN_SPAN_DAYS -> {
            for (year in start.year + 1..LocalDate.fromEpochDays(maxDay).year) {
                ticks += LocalDate(year, 1, 1).toEpochDays() to year.toString()
            }
        }
        else -> {
            val everyMonths = if (span >= QUARTER_TICKS_MIN_SPAN_DAYS) MONTHS_PER_QUARTER else 1
            var year = start.year
            var month = start.month.number
            while (true) {
                month += 1
                if (month > 12) {
                    month = 1
                    year += 1
                }
                val tick = LocalDate(year, month, 1)
                if (tick.toEpochDays() > maxDay) break
                if ((month - 1) % everyMonths == 0) {
                    val label = if (month == 1) year.toString() else "${tick.year}-${month.toString().padStart(2, '0')}"
                    ticks += tick.toEpochDays() to label
                }
            }
        }
    }
    return ticks
}

private fun Instant.toEpochDay(timeZone: TimeZone): Long = toLocalDateTime(timeZone).date.toEpochDays()

private fun Instant.toLocalDate(timeZone: TimeZone): LocalDate = toLocalDateTime(timeZone).date
