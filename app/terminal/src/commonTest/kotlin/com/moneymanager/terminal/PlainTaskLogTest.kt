package com.moneymanager.terminal

import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PlainTaskLogTest {
    private val started = TestTimeSource().markNow()
    private val lines = mutableListOf<String>()

    private fun task(
        id: Long,
        title: String,
        parentId: Long? = null,
        status: TaskStatus = TaskStatus.RUNNING,
        detail: String = "",
        elapsed: Duration? = null,
        issues: List<String> = emptyList(),
    ) = TaskState(id, "task-$id", title, parentId, status, Progress(detail), started, elapsed, issues)

    @Test
    fun startsFinishesAndProblemsArePrintedOnce() {
        val log = PlainTaskLog(sinceId = 1) { lines += it }

        log.update(listOf(task(1, "Import all"), task(2, "Monzo", parentId = 1)))
        log.update(listOf(task(1, "Import all"), task(2, "Monzo", parentId = 1, issues = listOf("page 2 retried"))))
        log.update(
            listOf(
                task(1, "Import all"),
                task(2, "Monzo", parentId = 1, TaskStatus.FAILED, "HTTP 500", 3.seconds, listOf("page 2 retried")),
            ),
        )
        log.update(
            listOf(
                task(1, "Import all", status = TaskStatus.SUCCEEDED, detail = "1 failed", elapsed = 65.seconds),
                task(2, "Monzo", parentId = 1, TaskStatus.FAILED, "HTTP 500", 3.seconds, listOf("page 2 retried")),
            ),
        )

        assertEquals(
            listOf(
                "started: Import all",
                "started: Import all › Monzo",
                "problem: Import all › Monzo: page 2 retried",
                "failed: Import all › Monzo — HTTP 500",
                "done: Import all — 1 failed (01:05)",
            ),
            lines,
        )
    }

    @Test
    fun aTaskThatStartedAndEndedBetweenSamplesGetsBothLines() {
        val log = PlainTaskLog(sinceId = 1) { lines += it }

        log.update(emptyList())
        log.update(listOf(task(1, "Sync", status = TaskStatus.CANCELLED, elapsed = 1.seconds)))

        assertEquals(listOf("started: Sync", "cancelled: Sync"), lines)
    }

    @Test
    fun tasksFromBeforeTheRunAreIgnored() {
        val log = PlainTaskLog(sinceId = 2) { lines += it }

        log.update(listOf(task(1, "Earlier"), task(2, "Now")))

        assertEquals(listOf("started: Now"), lines)
    }
}
