package com.moneymanager.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class TaskManagerTest {
    private val timeSource = TestTimeSource()

    // Tasks run in backgroundScope, which advanceUntilIdle ignores, so tests step with runCurrent instead.
    private fun TestScope.manager() = TaskManager(backgroundScope, timeSource, StandardTestDispatcher(testScheduler))

    private fun TaskManager.state(id: Long?) = tasks.value.single { it.id == id }

    @Test
    fun aTaskRunsAndRecordsItsResultAndElapsedTime() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            val id = manager.start("import", "Import", "Starting") { release.await().let { "Imported 3" } }
            runCurrent()
            assertEquals(TaskStatus.RUNNING, manager.state(id).status)
            assertEquals("Starting", manager.state(id).progress.detail)

            timeSource += 65.seconds
            assertEquals(65.seconds, manager.state(id).elapsed())
            release.complete(Unit)
            runCurrent()

            val finished = manager.state(id)
            assertEquals(TaskStatus.SUCCEEDED, finished.status)
            assertEquals("Imported 3", finished.progress.detail)
            timeSource += 10.seconds
            assertEquals(65.seconds, finished.elapsed(), "a finished task's elapsed time stops")
        }

    @Test
    fun reportUpdatesTheRunningTask() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            val id =
                manager.start("import", "Import", "Starting") {
                    report("Halfway", 0.5f)
                    release.await()
                    "Done"
                }
            runCurrent()

            assertEquals(Progress("Halfway", 0.5f), manager.state(id).progress)
            release.complete(Unit)
        }

    @Test
    fun aRunningKeyIsNotStartedTwiceButIsReusableOnceFinished() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            assertNotNull(manager.start("import", "Import", "Starting") { release.await().let { "Done" } })
            assertNull(manager.start("import", "Import", "Starting") { "Never runs" })
            assertTrue(manager.isRunning("import"))

            release.complete(Unit)
            runCurrent()
            assertTrue(!manager.isRunning("import"))
            assertNotNull(manager.start("import", "Import", "Starting") { "Again" })
        }

    @Test
    fun aThrowingTaskIsMarkedFailed() =
        runTest {
            val manager = manager()

            val id = manager.start("import", "Import", "Starting") { error("Bad file") }
            runCurrent()

            assertEquals(TaskStatus.FAILED, manager.state(id).status)
            assertEquals("Bad file", manager.state(id).progress.detail)
        }

    @Test
    fun cancellingAParentCancelsItsChildren() =
        runTest {
            val manager = manager()
            var childId: Long? = null

            val id =
                manager.start("all", "Import all", "Starting") {
                    childId = startChild("one", "One", "Waiting") { CompletableDeferred<String>().await() }
                    CompletableDeferred<String>().await()
                }
            runCurrent()
            assertEquals(TaskStatus.RUNNING, manager.state(childId).status)

            manager.cancel(assertNotNull(id))
            runCurrent()

            assertEquals(TaskStatus.CANCELLED, manager.state(id).status)
            assertEquals(TaskStatus.CANCELLED, manager.state(childId).status)
        }

    @Test
    fun aFailingChildDoesNotFailItsParentOrSiblings() =
        runTest {
            val manager = manager()
            var failingId: Long? = null
            var siblingId: Long? = null

            val id =
                manager.start("all", "Import all", "Starting") {
                    failingId = startChild("bad", "Bad", "Starting") { error("Unreachable folder") }
                    siblingId = startChild("good", "Good", "Starting") { "Downloaded 2" }
                    "All done"
                }
            runCurrent()

            assertEquals(TaskStatus.FAILED, manager.state(failingId).status)
            assertEquals(TaskStatus.SUCCEEDED, manager.state(siblingId).status)
            assertEquals(TaskStatus.SUCCEEDED, manager.state(id).status)
            assertEquals(id, manager.state(siblingId).parentId)
        }

    @Test
    fun aParentFinishesOnlyAfterItsChildren() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            val id =
                manager.start("all", "Import all", "Starting") {
                    startChild("one", "One", "Starting") { release.await().let { "Done" } }
                    "All done"
                }
            runCurrent()
            assertEquals(TaskStatus.RUNNING, manager.state(id).status)

            release.complete(Unit)
            runCurrent()
            assertEquals(TaskStatus.SUCCEEDED, manager.state(id).status)
        }

    @Test
    fun anAwaitedChildReturnsItsResultAndRethrowsItsFailure() =
        runTest {
            val manager = manager()
            var result: Int? = null
            var failure: String? = null

            manager.start("all", "Import all", "Starting") {
                result = child("one", "One", "Starting") { 42 }
                failure = runCatching { child("two", "Two", "Starting") { error("Boom") } }.exceptionOrNull()?.message
                "All done"
            }
            runCurrent()

            assertEquals(42, result)
            assertEquals("Boom", failure)
            assertEquals(
                listOf(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED, TaskStatus.FAILED),
                manager.tasks.value.map { it.status },
            )
        }

    @Test
    fun aParentWithoutItsOwnFractionAveragesItsChildren() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            val id =
                manager.start("all", "Import all", "Starting") {
                    startChild("one", "One", "Starting") { "Done" }
                    startChild("two", "Two", "Starting") {
                        report("Halfway", 0.5f)
                        release.await()
                        "Done"
                    }
                    startChild("three", "Three", "Starting") { release.await().let { "Done" } }
                    release.await()
                    "All done"
                }
            runCurrent()

            // Finished = 1, reported = 0.5, nothing known yet = 0.
            assertEquals(0.5f, manager.tasks.value.aggregateFraction(assertNotNull(id)))

            manager.tasks.value
                .single { it.key == "two" }
                .let { assertEquals(0.5f, manager.tasks.value.aggregateFraction(it.id)) }
            release.complete(Unit)
            runCurrent()
            assertEquals(1f, manager.tasks.value.aggregateFraction(id))
        }

    @Test
    fun aParentsOwnFractionOverridesItsChildren() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()

            val id =
                manager.start("all", "Import all", "Starting") {
                    startChild("one", "One", "Starting") { "Done" }
                    report("Overall", 0.1f)
                    release.await()
                    "All done"
                }
            runCurrent()

            assertEquals(0.1f, manager.tasks.value.aggregateFraction(assertNotNull(id)))
            assertNull(manager.tasks.value.aggregateFraction(-1))
            release.complete(Unit)
        }

    @Test
    fun onlyTheLastTwentyFinishedTasksAreKeptWithTheirChildren() =
        runTest {
            val manager = manager()
            val release = CompletableDeferred<Unit>()
            manager.start("running", "Running", "Starting") { release.await().let { "Done" } }

            repeat(25) { index ->
                manager.start("task-$index", "Task $index", "Starting") {
                    startChild("child-$index", "Child $index", "Starting") { "Done" }
                    "Done"
                }
                runCurrent()
            }

            val topLevel = manager.tasks.value.filter { it.parentId == null }
            assertEquals(listOf("running") + (5 until 25).map { "task-$it" }, topLevel.map { it.key })
            val children = manager.tasks.value.filter { it.parentId != null }
            assertEquals((5 until 25).map { "child-$it" }, children.map { it.key })
            release.complete(Unit)
        }
}
