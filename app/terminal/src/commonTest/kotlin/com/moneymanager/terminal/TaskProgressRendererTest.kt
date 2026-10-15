package com.moneymanager.terminal

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.moneymanager.tasks.TaskManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TaskProgressRendererTest {
    private val written = mutableListOf<String>()
    private var interrupt: () -> Unit = {}
    private var interruptsClosed = false
    private val interrupts =
        Interrupts { _, onInterrupt ->
            interrupt = onInterrupt
            AutoCloseable { interruptsClosed = true }
        }

    private fun renderer(mode: ProgressMode) =
        TaskProgressRenderer(
            mode,
            StderrRegion {
                written += it
            },
            width = { 60 },
            AnsiLevel.NONE,
            interrupts,
        )

    private fun TestScope.manager() = TaskManager(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))

    private suspend fun TaskManager.awaitIdle() = tasks.first { all -> all.isNotEmpty() && all.none { it.isRunning } }

    @Test
    fun liveBarsEndWithTheSummaryOnScreen() =
        runTest {
            val manager = manager()

            val result =
                renderer(ProgressMode.LIVE).render(manager) {
                    manager.start("import", "Import", "Starting") {
                        delay(1.seconds)
                        "Imported 3"
                    }
                    manager.awaitIdle()
                    "ok"
                }

            assertEquals("ok", result)
            assertTrue(written.any { "Import" in it && "Starting" in it }, "a frame showed the running task: $written")
            assertTrue(written.last().contains("✓ Import — Imported 3 (00:00)"), written.last())
            assertTrue(written.last().endsWith(StderrRegion.SHOW_CURSOR))
            assertTrue(interruptsClosed)
        }

    @Test
    fun ctrlCCancelsTheTasksShowsTheSummaryAndThrows() =
        runTest {
            val manager = manager()
            val never = CompletableDeferred<String>()
            launch {
                delay(2.seconds)
                interrupt()
            }

            assertFailsWith<TasksInterruptedException> {
                renderer(ProgressMode.LIVE).render(manager) {
                    manager.start("import", "Import", "Starting") { never.await() }
                    manager.awaitIdle()
                }
            }

            assertTrue(written.last().contains("⊘ Import — cancelled"), written.last())
            assertTrue(manager.tasks.value.none { it.isRunning })
        }

    @Test
    fun plainModePrintsOneLinePerChange() =
        runTest {
            val manager = manager()

            renderer(ProgressMode.PLAIN).render(manager) {
                manager.start("import", "Import", "Starting") { "Imported 3" }
                manager.awaitIdle()
            }

            assertEquals(listOf("started: Import\n", "done: Import — Imported 3 (00:00)\n"), written)
        }

    @Test
    fun offModeWritesNothing() =
        runTest {
            val manager = manager()

            renderer(ProgressMode.OFF).render(manager) {
                manager.start("import", "Import", "Starting") { "Imported 3" }
                manager.awaitIdle()
            }

            assertEquals(emptyList(), written)
        }

    @Test
    fun aFailingBlockStillLeavesTheSummary() =
        runTest {
            val manager = manager()

            assertFailsWith<IllegalStateException> {
                renderer(ProgressMode.LIVE).render(manager) {
                    manager.start("import", "Import", "Starting") { "Imported 3" }
                    manager.awaitIdle()
                    error("boom")
                }
            }

            assertTrue(written.last().contains("✓ Import"), written.last())
        }

    @Test
    fun tasksFromEarlierRunsAreNotShown() =
        runTest {
            val manager = manager()
            manager.start("earlier", "Earlier", "Starting") { "Done" }
            manager.awaitIdle()

            renderer(ProgressMode.PLAIN).render(manager) {
                manager.start("now", "Now", "Starting") { "Done" }
                manager.tasks.first { all -> all.size == 2 && all.none { it.isRunning } }
            }

            assertEquals(listOf("started: Now\n", "done: Now — Done (00:00)\n"), written)
        }
}
