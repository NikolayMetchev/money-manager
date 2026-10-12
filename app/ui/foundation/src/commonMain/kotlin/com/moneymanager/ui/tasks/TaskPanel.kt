package com.moneymanager.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.tasks.aggregateFraction
import com.moneymanager.tasks.childrenOf
import com.moneymanager.tasks.formatElapsedTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

private const val VISIBLE_TASKS = 3
private const val VISIBLE_CHILDREN = 5
private const val VISIBLE_ISSUES = 3

val LocalTaskManager =
    compositionLocalOf<TaskManager> {
        error("No TaskManager provided.")
    }

@Composable
fun rememberTaskManager(scope: CoroutineScope): TaskManager = remember(scope) { TaskManager(scope) }

/** Shows the latest top-level tasks, each with its running children, collected from [manager]. */
@Composable
fun TaskPanel(
    manager: TaskManager,
    modifier: Modifier = Modifier,
) {
    val tasks by manager.tasks.collectAsState()
    val visibleTasks = visibleTopLevelTasks(tasks)
    if (visibleTasks.isEmpty()) return

    val hasRunningTask = visibleTasks.any { it.isRunning }
    // Ticks once a second while anything runs, so elapsed times recompose.
    val tick by produceState(initialValue = 0, key1 = hasRunningTask) {
        while (hasRunningTask) {
            delay(1.seconds)
            value++
        }
    }
    val latestTaskId = visibleTasks.lastOrNull()?.id
    var expanded by remember { mutableStateOf(hasRunningTask) }
    var lastSeenTaskId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(latestTaskId) {
        if (latestTaskId != null && latestTaskId != lastSeenTaskId) {
            lastSeenTaskId = latestTaskId
            expanded = visibleTasks.last().isRunning
        }
    }

    LaunchedEffect(hasRunningTask) {
        if (!hasRunningTask) {
            expanded = false
        }
    }

    if (!expanded) {
        CollapsedTaskPanel(
            taskCount = visibleTasks.size,
            hasRunningTask = hasRunningTask,
            onClick = { expanded = true },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.widthIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Background Tasks",
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { expanded = false }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Hide background tasks",
                )
            }
        }
        visibleTasks.forEach { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TaskRow(task = task, tasks = tasks, tick = tick, onCancel = { manager.cancel(task.id) })
                    ChildTasks(children = tasks.childrenOf(task.id), tasks = tasks, tick = tick)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskState,
    tasks: List<TaskState>,
    tick: Int,
    onCancel: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = task.title,
            style = if (task.parentId == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
        )
        Text(
            text = task.status.label(),
            style = MaterialTheme.typography.labelSmall,
            color = task.status.color(),
        )
    }
    Text(
        text = task.progress.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Keyed on the tick so a running task's elapsed time moves every second.
    val elapsed = remember(task, tick) { task.elapsed() }
    if (task.isRunning) {
        Text(
            text = "Elapsed ${formatElapsedTime(elapsed)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fraction = tasks.aggregateFraction(task.id)
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    } else if (task.parentId == null) {
        Text(
            text = "Took ${formatElapsedTime(elapsed)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    task.issues.take(VISIBLE_ISSUES).forEach { issue ->
        Text(
            text = issue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (task.issues.size > VISIBLE_ISSUES) {
        Text(
            text = "+${task.issues.size - VISIBLE_ISSUES} more problem(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// Running tasks always stay visible (so they can be watched and cancelled); finished ones fill the
// remaining slots, newest first. Shown in start order.
private fun visibleTopLevelTasks(tasks: List<TaskState>): List<TaskState> {
    val topLevel = tasks.filter { it.parentId == null }
    val running = topLevel.filter { it.isRunning }
    val finished = topLevel.filterNot { it.isRunning }.takeLast((VISIBLE_TASKS - running.size).coerceAtLeast(0))
    val shown = (running + finished).mapTo(mutableSetOf()) { it.id }
    return topLevel.filter { it.id in shown }
}

// Running children first, capped so a bulk run over dozens of folders keeps the panel readable.
@Composable
private fun ChildTasks(
    children: List<TaskState>,
    tasks: List<TaskState>,
    tick: Int,
) {
    if (children.isEmpty()) return
    val finished = children.count { !it.isRunning }
    val failed = children.count { it.status == TaskStatus.FAILED }
    Text(
        text = "$finished of ${children.size} done" + if (failed > 0) ", $failed failed" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Failed children stay listed after they finish: a bulk child can fail while its parent succeeds.
    val shown =
        (children.filter { it.isRunning } + children.filter { it.status == TaskStatus.FAILED })
            .take(VISIBLE_CHILDREN)
    shown.forEach { child ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TaskRow(task = child, tasks = tasks, tick = tick, onCancel = null)
        }
    }
    val hidden = children.count { it.isRunning || it.status == TaskStatus.FAILED } - shown.size
    if (hidden > 0) {
        Text(
            text = "+$hidden more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun CollapsedTaskPanel(
    taskCount: Int,
    hasRunningTask: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.widthIn(max = 260.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "$taskCount background task(s)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (hasRunningTask) "Running" else "Done",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (hasRunningTask) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                )
            }
            if (hasRunningTask) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TaskStatus.color() =
    when (this) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.tertiary
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun TaskStatus.label(): String =
    when (this) {
        TaskStatus.RUNNING -> "Running"
        TaskStatus.SUCCEEDED -> "Done"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.CANCELLED -> "Cancelled"
    }
