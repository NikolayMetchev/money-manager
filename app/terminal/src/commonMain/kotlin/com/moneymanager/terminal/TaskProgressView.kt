package com.moneymanager.terminal

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.tasks.aggregateFraction
import com.moneymanager.tasks.childrenOf
import com.moneymanager.tasks.formatElapsedTime
import kotlin.math.roundToInt

private const val VISIBLE_RUNNING_CHILDREN = 8
private const val VISIBLE_FINISHED_CHILDREN = 3
private const val VISIBLE_ISSUES = 3
private const val MIN_BAR_WIDTH = 10
private const val MAX_BAR_WIDTH = 30
private const val INDENT = "  "

/**
 * Every top-level task from [sinceId] on, in start order: a running task as a bar with its percentage and
 * elapsed time, a detail line and its children (running ones as bars, the latest finished ones collapsed);
 * a finished task as one ✓/✗/⊘ line, followed by any children that failed. [frame] moves indeterminate bars.
 * Each line is cut to [width] columns, so a terminal never wraps it and the region redraws in place.
 */
@Composable
fun TaskProgressView(
    tasks: List<TaskState>,
    width: Int,
    sinceId: Long = 0,
    frame: Int = 0,
) {
    Column {
        tasks.filter { it.parentId == null && it.id >= sinceId }.forEach { task ->
            TaskLines(task, tasks, depth = 0, width = width, frame = frame)
        }
    }
}

@Composable
private fun TaskLines(
    task: TaskState,
    tasks: List<TaskState>,
    depth: Int,
    width: Int,
    frame: Int,
) {
    val indent = INDENT.repeat(depth)
    val children = tasks.childrenOf(task.id)
    if (task.isRunning) {
        RunningTaskRow(task, tasks, indent, width, frame, detailInline = depth > 0)
        if (depth == 0) Line("$indent$INDENT${task.progress.detail}", width, textStyle = TextStyle.Dim)
        val running = children.filter { it.isRunning }
        val finished = children.filterNot { it.isRunning }
        finished.takeLast(VISIBLE_FINISHED_CHILDREN).forEach { TaskLines(it, tasks, depth + 1, width, frame) }
        if (finished.size > VISIBLE_FINISHED_CHILDREN) {
            Line("$indent$INDENT+${finished.size - VISIBLE_FINISHED_CHILDREN} more finished", width, textStyle = TextStyle.Dim)
        }
        running.take(VISIBLE_RUNNING_CHILDREN).forEach { TaskLines(it, tasks, depth + 1, width, frame) }
        if (running.size > VISIBLE_RUNNING_CHILDREN) {
            Line("$indent$INDENT+${running.size - VISIBLE_RUNNING_CHILDREN} more running", width, textStyle = TextStyle.Dim)
        }
    } else {
        FinishedTaskLine(task, indent, width)
        // A finished task's summary keeps the children that went wrong; the ones that worked fold into it.
        children
            .filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
            .forEach { TaskLines(it, tasks, depth + 1, width, frame) }
    }
    Issues(task.issues, "$indent$INDENT", width)
}

@Composable
private fun RunningTaskRow(
    task: TaskState,
    tasks: List<TaskState>,
    indent: String,
    width: Int,
    frame: Int,
    detailInline: Boolean,
) {
    val fraction = tasks.aggregateFraction(task.id)
    val percent = fraction?.let { "${(it * 100).roundToInt()}%".padStart(4) } ?: "    "
    val stats = " $percent  ${formatElapsedTime(task.elapsed())}"
    // The bar gives way first on a narrow terminal, so the title keeps a few readable columns.
    val barWidth =
        (width / 4)
            .coerceIn(MIN_BAR_WIDTH, MAX_BAR_WIDTH)
            .coerceAtMost(width - indent.length - stats.length - 1 - MIN_TITLE_WIDTH)
            .coerceAtLeast(0)
    val fixed = indent.length + barWidth + stats.length + 1
    // A child's detail follows on the same line, so its title gets a fixed share and the detail the rest; a
    // top-level task's detail has a line of its own, so its title takes whatever the bar leaves.
    val titleWidth =
        if (detailInline) {
            (width / 4).coerceIn(MIN_TITLE_WIDTH, MAX_INLINE_TITLE_WIDTH)
        } else {
            (width - fixed).coerceAtLeast(MIN_TITLE_WIDTH)
        }
    Row {
        Text(indent + fit(task.title, titleWidth).padEnd(titleWidth) + " ")
        ProgressBar(fraction, barWidth, frame)
        Text(stats)
        if (detailInline) Text(fit("  ${task.progress.detail}", width - fixed - titleWidth), textStyle = TextStyle.Dim)
    }
}

@Composable
private fun FinishedTaskLine(
    task: TaskState,
    indent: String,
    width: Int,
) {
    val (symbol, color) = task.status.symbol()
    val detail = if (task.status == TaskStatus.CANCELLED) "cancelled" else task.progress.detail
    val elapsed = if (task.status == TaskStatus.SUCCEEDED) " (${formatElapsedTime(task.elapsed())})" else ""
    Row {
        Text(indent)
        Text(symbol, color = color)
        Text(fit(" ${task.title} — $detail$elapsed", (width - indent.length - symbol.length).coerceAtLeast(0)))
    }
}

@Composable
private fun Issues(
    issues: List<String>,
    indent: String,
    width: Int,
) {
    issues.take(VISIBLE_ISSUES).forEach { Line("$indent! $it", width, color = Color.Red) }
    if (issues.size > VISIBLE_ISSUES) {
        Line("$indent+${issues.size - VISIBLE_ISSUES} more problem(s)", width, color = Color.Red)
    }
}

/**
 * A bar [width] cells wide: [fraction] of it filled, or, when the fraction is unknown, a short segment that
 * slides back and forth as [frame] advances.
 */
@Composable
fun ProgressBar(
    fraction: Float?,
    width: Int,
    frame: Int = 0,
) {
    Text(progressBar(fraction, width, frame))
}

internal fun progressBar(
    fraction: Float?,
    width: Int,
    frame: Int,
): String {
    if (width <= 0) return ""
    if (fraction != null) {
        val filled = (fraction.coerceIn(0f, 1f) * width).roundToInt()
        return FILLED.repeat(filled) + EMPTY.repeat(width - filled)
    }
    val segment = (width / SEGMENT_DIVISOR).coerceAtLeast(1)
    val travel = width - segment
    // Bounces: 0 → travel → 0, one cell per frame.
    val period = (travel * 2).coerceAtLeast(1)
    val step = frame.mod(period)
    val start = if (step <= travel) step else period - step
    return EMPTY.repeat(start) + FILLED.repeat(segment) + EMPTY.repeat(width - start - segment)
}

@Composable
private fun Line(
    text: String,
    width: Int,
    color: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
) {
    Text(fit(text, width), color = color, textStyle = textStyle)
}

private fun TaskStatus.symbol(): Pair<String, Color> =
    when (this) {
        TaskStatus.SUCCEEDED -> "✓" to Color.Green
        TaskStatus.FAILED -> "✗" to Color.Red
        TaskStatus.CANCELLED -> "⊘" to Color.Yellow
        TaskStatus.RUNNING -> "…" to Color.Unspecified
    }

/** [text] on one line, cut to [width] columns and ending in an ellipsis when it was cut. */
internal fun fit(
    text: String,
    width: Int,
): String {
    val line = text.replace(LINE_BREAKS, " ")
    return when {
        line.length <= width -> line
        width <= 0 -> ""
        else -> line.take(width - 1) + "…"
    }
}

private val LINE_BREAKS = Regex("[\\r\\n\\t]+")

private const val FILLED = "█"
private const val EMPTY = "░"
private const val SEGMENT_DIVISOR = 4
private const val MIN_TITLE_WIDTH = 8
private const val MAX_INLINE_TITLE_WIDTH = 24
