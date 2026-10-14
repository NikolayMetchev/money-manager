package com.moneymanager.terminal

import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.tasks.formatElapsedTime

/**
 * Progress as plain lines, for when bars can't be drawn: one line when a task starts, one when it ends,
 * and one per problem it reports. Each [update] diffs a snapshot of the tasks against the last one, so
 * sampling the tasks only now and then loses nothing: a task that started and finished in between still
 * gets both lines.
 */
internal class PlainTaskLog(
    private val sinceId: Long,
    private val print: (String) -> Unit,
) {
    private val seen = mutableMapOf<Long, TaskState>()

    fun update(tasks: List<TaskState>) {
        val byId = tasks.associateBy { it.id }
        tasks.filter { it.id >= sinceId }.forEach { task ->
            val previous = seen[task.id]
            val name = name(task, byId)
            if (previous == null) print("started: $name")
            task.issues.drop(previous?.issues?.size ?: 0).forEach { print("problem: $name: $it") }
            if (!task.isRunning && previous?.isRunning != false) print(finished(task, name))
            seen[task.id] = task
        }
    }

    private fun finished(
        task: TaskState,
        name: String,
    ): String =
        when (task.status) {
            TaskStatus.SUCCEEDED -> "done: $name — ${task.progress.detail} (${formatElapsedTime(task.elapsed())})"
            TaskStatus.FAILED -> "failed: $name — ${task.progress.detail}"
            TaskStatus.CANCELLED -> "cancelled: $name"
            TaskStatus.RUNNING -> "running: $name"
        }

    // A child is named after its parents too ("Import all › Monzo"), since its line has no indentation to show that.
    private fun name(
        task: TaskState,
        byId: Map<Long, TaskState>,
    ): String = task.parentId?.let { byId[it] ?: seen[it] }?.let { "${name(it, byId)} › ${task.title}" } ?: task.title
}
