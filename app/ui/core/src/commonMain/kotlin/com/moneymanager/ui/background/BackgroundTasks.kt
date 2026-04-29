package com.moneymanager.ui.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class BackgroundTaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class BackgroundTask(
    val id: Long,
    val key: String,
    val title: String,
    val detail: String,
    val status: BackgroundTaskStatus,
)

class BackgroundTaskController internal constructor(
    private val updateDetail: (String) -> Unit,
) {
    fun update(detail: String) {
        updateDetail(detail)
    }
}

@Stable
class BackgroundTaskManager(
    private val scope: CoroutineScope,
) {
    private var nextTaskId = 1L
    val tasks = mutableStateListOf<BackgroundTask>()

    fun isRunning(key: String): Boolean = tasks.any { task -> task.key == key && task.status == BackgroundTaskStatus.RUNNING }

    fun startTask(
        key: String,
        title: String,
        initialDetail: String,
        block: suspend BackgroundTaskController.() -> String,
    ) {
        if (isRunning(key)) return

        val taskId = nextTaskId++
        tasks.add(
            BackgroundTask(
                id = taskId,
                key = key,
                title = title,
                detail = initialDetail,
                status = BackgroundTaskStatus.RUNNING,
            ),
        )

        val controller =
            BackgroundTaskController { detail ->
                updateTask(taskId) { task -> task.copy(detail = detail) }
            }

        scope.launch {
            try {
                val finalDetail = controller.block()
                updateTask(taskId) { task ->
                    task.copy(detail = finalDetail, status = BackgroundTaskStatus.SUCCEEDED)
                }
            } catch (expected: Exception) {
                updateTask(taskId) { task ->
                    task.copy(
                        detail = expected.message ?: "Task failed.",
                        status = BackgroundTaskStatus.FAILED,
                    )
                }
            }
        }
    }

    private fun updateTask(
        taskId: Long,
        transform: (BackgroundTask) -> BackgroundTask,
    ) {
        val index = tasks.indexOfFirst { task -> task.id == taskId }
        if (index >= 0) {
            tasks[index] = transform(tasks[index])
        }
    }
}

val LocalBackgroundTaskManager =
    compositionLocalOf<BackgroundTaskManager> {
        error("No BackgroundTaskManager provided.")
    }

@Composable
fun rememberBackgroundTaskManager(scope: CoroutineScope): BackgroundTaskManager = remember(scope) { BackgroundTaskManager(scope) }

@Composable
fun BackgroundTaskPanel(
    manager: BackgroundTaskManager,
    modifier: Modifier = Modifier,
) {
    val visibleTasks = manager.tasks.takeLast(3)
    if (visibleTasks.isEmpty()) return

    Column(
        modifier = modifier.widthIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleTasks.forEach { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = task.status.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = task.status.color(),
                        )
                    }
                    Text(
                        text = task.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (task.status == BackgroundTaskStatus.RUNNING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundTaskStatus.color() =
    when (this) {
        BackgroundTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        BackgroundTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.tertiary
        BackgroundTaskStatus.FAILED -> MaterialTheme.colorScheme.error
    }

private fun BackgroundTaskStatus.label(): String =
    when (this) {
        BackgroundTaskStatus.RUNNING -> "Running"
        BackgroundTaskStatus.SUCCEEDED -> "Done"
        BackgroundTaskStatus.FAILED -> "Failed"
    }
