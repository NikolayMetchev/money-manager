package com.moneymanager.terminal

import com.jakewharton.mosaic.testing.runMosaicTest
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TaskProgressViewTest {
    private val time = TestTimeSource()
    private val started = time.markNow()

    private fun task(
        id: Long,
        title: String,
        detail: String,
        parentId: Long? = null,
        status: TaskStatus = TaskStatus.RUNNING,
        fraction: Float? = null,
        elapsed: Duration? = null,
        issues: List<String> = emptyList(),
    ) = TaskState(
        id = id,
        key = "task-$id",
        title = title,
        parentId = parentId,
        status = status,
        progress = Progress(detail, fraction),
        startedAt = started,
        elapsedAtCompletion = elapsed,
        issues = issues,
    )

    private suspend fun snapshot(
        tasks: List<TaskState>,
        width: Int = 60,
        sinceId: Long = 0,
        frame: Int = 0,
    ): String {
        var text = ""
        runMosaicTest { text = setContentAndSnapshot { TaskProgressView(tasks, width, sinceId, frame) } }
        return text
    }

    @Test
    fun aRunningTaskShowsItsBarDetailAndChildren() =
        runTest {
            time += 83.seconds
            val tasks =
                listOf(
                    task(1, "Import all", "Downloading 2 folders"),
                    task(2, "Monzo", "Page 3", parentId = 1, fraction = 0.5f),
                    task(3, "Wise", "Imported 12", parentId = 1, status = TaskStatus.SUCCEEDED, elapsed = 12.seconds),
                    task(4, "Revolut", "HTTP 500", parentId = 1, status = TaskStatus.FAILED, elapsed = 3.seconds),
                )

            assertEquals(
                """
                Import all                       █████████████░░  83%  01:23
                  Downloading 2 folders
                  ✓ Wise — Imported 12 (00:12)
                  ✗ Revolut — HTTP 500
                  Monzo           ████████░░░░░░░  50%  01:23  Page 3
                """.trimIndent(),
                snapshot(tasks),
            )
        }

    @Test
    fun aFinishedTaskCollapsesToOneLineKeepingFailedChildrenAndIssues() =
        runTest {
            val tasks =
                listOf(
                    task(
                        1,
                        "Import all",
                        "2 imported",
                        status = TaskStatus.SUCCEEDED,
                        elapsed = 75.seconds,
                        issues = listOf("a.csv: no strategy"),
                    ),
                    task(2, "Wise", "Imported 12", parentId = 1, status = TaskStatus.SUCCEEDED, elapsed = 12.seconds),
                    task(3, "Monzo", "Cancelled.", parentId = 1, status = TaskStatus.CANCELLED, elapsed = 3.seconds),
                )

            assertEquals(
                """
                ✓ Import all — 2 imported (01:15)
                  ⊘ Monzo — cancelled
                  ! a.csv: no strategy
                """.trimIndent(),
                snapshot(tasks),
            )
        }

    @Test
    fun tasksStartedBeforeTheRunAreLeftOut() =
        runTest {
            val tasks =
                listOf(
                    task(1, "Earlier", "Done", status = TaskStatus.SUCCEEDED, elapsed = 1.seconds),
                    task(2, "Now", "Done", status = TaskStatus.SUCCEEDED, elapsed = 2.seconds),
                )

            assertEquals("✓ Now — Done (00:02)", snapshot(tasks, sinceId = 2))
        }

    @Test
    fun linesAreCutToTheWidth() =
        runTest {
            val tasks = listOf(task(1, "A task with a very long title indeed", "and a very long detail line too", fraction = 1f))

            val lines = snapshot(tasks, width = 30).lines()

            assertEquals(listOf(30, 30), lines.map { it.length })
            assertEquals("  and a very long detail line…", lines[1])
        }

    @Test
    fun theProblemListIsCapped() =
        runTest {
            val issues = (1..5).map { "problem $it" }
            val tasks = listOf(task(1, "Import", "Failed", status = TaskStatus.FAILED, elapsed = 1.seconds, issues = issues))

            assertEquals(
                """
                ✗ Import — Failed
                  ! problem 1
                  ! problem 2
                  ! problem 3
                  +2 more problem(s)
                """.trimIndent(),
                snapshot(tasks),
            )
        }

    @Test
    fun aKnownFractionFillsTheBar() {
        assertEquals("█████░░░░░", progressBar(0.5f, 10, frame = 0))
        assertEquals("██████████", progressBar(1.5f, 10, frame = 0))
    }

    @Test
    fun anUnknownFractionSlidesASegmentBackAndForth() {
        val frames = (0..16).map { progressBar(null, 10, frame = it) }

        assertEquals("██░░░░░░░░", frames[0])
        assertEquals("░██░░░░░░░", frames[1])
        assertEquals("░░░░░░░░██", frames[8])
        assertEquals("░░░░░░░██░", frames[9])
        assertEquals(frames[0], frames[16])
    }
}
