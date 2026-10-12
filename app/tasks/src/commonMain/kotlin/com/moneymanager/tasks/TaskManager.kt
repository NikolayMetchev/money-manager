package com.moneymanager.tasks

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

private val logger = Logger.withTag("TaskManager")

enum class TaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** A snapshot of one task. [parentId] links a child task to the task that started it. */
data class TaskState(
    val id: Long,
    val key: String,
    val title: String,
    val parentId: Long?,
    val status: TaskStatus,
    val progress: Progress,
    val startedAt: ComparableTimeMark,
    val elapsedAtCompletion: Duration? = null,
) {
    val isRunning: Boolean get() = status == TaskStatus.RUNNING

    /** How long the task ran, or has been running so far. */
    fun elapsed(): Duration = elapsedAtCompletion ?: startedAt.elapsedNow()
}

/** What a task's block runs in: its own coroutine scope, plus progress reporting and child tasks. */
interface TaskScope : CoroutineScope {
    val taskId: Long

    fun report(progress: Progress)

    fun report(
        detail: String,
        fraction: Float? = null,
    ) = report(Progress(detail, fraction))

    /**
     * Starts a child task that runs alongside the rest of this block; this task finishes only once all
     * its children have. A failing child is marked failed without failing this task or its siblings.
     * Returns null (running nothing) while a task with [key] is still running.
     */
    fun startChild(
        key: String,
        title: String,
        initialDetail: String,
        block: suspend TaskScope.() -> String,
    ): Long?

    /**
     * Runs [block] as a child task and returns its result. A failure marks the child failed and is
     * rethrown to the caller. The child keeps the last detail it reported.
     */
    suspend fun <T> child(
        key: String,
        title: String,
        initialDetail: String,
        block: suspend TaskScope.() -> T,
    ): T
}

/**
 * Runs background tasks and publishes their state as [tasks], for the UI, CLI progress bars or a TUI to
 * collect. Tasks are deduplicated by key while running, can start child tasks (so one "import all"
 * shows a bar per directory under an overall bar), and can be cancelled.
 *
 * All state lives in [MutableStateFlow]s updated with compare-and-set, so starting, reporting and
 * finishing are safe from any thread on every platform.
 */
class TaskManager(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private companion object {
        const val MAX_RETAINED_TASKS = 20
    }

    private val nextId = MutableStateFlow(1L)
    private val mutableTasks = MutableStateFlow<List<TaskState>>(emptyList())
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())

    /** Every retained task, in start order: running ones plus the last [MAX_RETAINED_TASKS] finished. */
    val tasks: StateFlow<List<TaskState>> = mutableTasks.asStateFlow()

    fun isRunning(key: String): Boolean = mutableTasks.value.isRunning(key)

    /**
     * Starts [block] as a top-level task, or returns null (running nothing) while a task with [key] is
     * still running. The string [block] returns becomes the finished task's detail.
     */
    fun start(
        key: String,
        title: String,
        initialDetail: String,
        block: suspend TaskScope.() -> String,
    ): Long? = launchTask(scope, parentId = null, key, title, initialDetail, block)

    /** Cancels task [id] and its children. */
    fun cancel(id: Long) {
        jobs.value[id]?.cancel()
    }

    private fun launchTask(
        launchScope: CoroutineScope,
        parentId: Long?,
        key: String,
        title: String,
        initialDetail: String,
        block: suspend TaskScope.() -> String,
    ): Long? {
        val task = register(key, title, initialDetail, parentId, dedupe = true) ?: return null
        val job =
            launchScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                try {
                    execute(task, block) { it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    // Already recorded as FAILED; a background task's failure must not take its scope down.
                    logger.d { "Task ${task.key} failed: ${expected.message}" }
                }
            }
        jobs.update { it + (task.id to job) }
        job.invokeOnCompletion { jobs.update { it - task.id } }
        job.start()
        return task.id
    }

    private suspend fun <T> execute(
        task: TaskState,
        block: suspend TaskScope.() -> T,
        finalDetail: (T) -> String?,
    ): T {
        try {
            val result = supervisorScope { TaskScopeImpl(this, task.id).block() }
            finish(task, TaskStatus.SUCCEEDED, finalDetail(result))
            return result
        } catch (cancelled: CancellationException) {
            finish(task, TaskStatus.CANCELLED, "Cancelled.")
            throw cancelled
        } catch (expected: Exception) {
            logger.e(expected) { "Task ${task.title} failed: ${expected.message}" }
            finish(task, TaskStatus.FAILED, expected.message ?: "Task failed.")
            throw expected
        }
    }

    private fun register(
        key: String,
        title: String,
        initialDetail: String,
        parentId: Long?,
        dedupe: Boolean,
    ): TaskState? {
        val task =
            TaskState(
                id = nextId.getAndUpdate { it + 1 },
                key = key,
                title = title,
                parentId = parentId,
                status = TaskStatus.RUNNING,
                progress = Progress(initialDetail),
                startedAt = timeSource.markNow(),
            )
        var added = false
        mutableTasks.update { current ->
            added = !(dedupe && current.isRunning(key))
            if (added) current + task else current
        }
        return task.takeIf { added }
    }

    private fun report(
        id: Long,
        progress: Progress,
    ) {
        mutableTasks.update { current ->
            current.map { task -> if (task.id == id && task.isRunning) task.copy(progress = progress) else task }
        }
    }

    private fun finish(
        task: TaskState,
        status: TaskStatus,
        detail: String?,
    ) {
        val elapsed = task.startedAt.elapsedNow()
        mutableTasks.update { current ->
            val finished =
                current.map {
                    if (it.id == task.id && it.isRunning) {
                        it.copy(
                            status = status,
                            progress = if (detail == null) it.progress else it.progress.copy(detail = detail),
                            elapsedAtCompletion = elapsed,
                        )
                    } else {
                        it
                    }
                }
            if (task.parentId == null) pruned(finished) else finished
        }
    }

    private fun pruned(current: List<TaskState>): List<TaskState> {
        val removed =
            current
                .filter { it.parentId == null && !it.isRunning }
                .dropLast(MAX_RETAINED_TASKS)
                .mapTo(mutableSetOf()) { it.id }
        if (removed.isEmpty()) return current
        // Children always start after their parent, so one pass in start order reaches every descendant.
        current.forEach { if (it.parentId in removed) removed += it.id }
        return current.filterNot { it.id in removed }
    }

    private inner class TaskScopeImpl(
        coroutineScope: CoroutineScope,
        override val taskId: Long,
    ) : TaskScope,
        CoroutineScope by coroutineScope {
        override fun report(progress: Progress) = report(taskId, progress)

        override fun startChild(
            key: String,
            title: String,
            initialDetail: String,
            block: suspend TaskScope.() -> String,
        ): Long? = launchTask(this, parentId = taskId, key, title, initialDetail, block)

        override suspend fun <T> child(
            key: String,
            title: String,
            initialDetail: String,
            block: suspend TaskScope.() -> T,
        ): T {
            val task = checkNotNull(register(key, title, initialDetail, taskId, dedupe = false))
            return execute(task, block) { null }
        }
    }
}

/** Whether a task with [key] is running. */
fun List<TaskState>.isRunning(key: String): Boolean = any { it.key == key && it.isRunning }

/** The tasks [parentId] started, in start order. */
fun List<TaskState>.childrenOf(parentId: Long): List<TaskState> = filter { it.parentId == parentId }

/**
 * Task [id]'s completion in [0, 1]: its own reported fraction when it has one, otherwise the mean of its
 * children's (a finished child counts as complete). Null when nothing is known yet.
 */
fun List<TaskState>.aggregateFraction(id: Long): Float? {
    val task = firstOrNull { it.id == id } ?: return null
    if (!task.isRunning) return 1f
    task.progress.fraction?.let { return it }
    val children = childrenOf(id)
    if (children.isEmpty()) return null
    return children.map { aggregateFraction(it.id) ?: 0f }.average().toFloat()
}
