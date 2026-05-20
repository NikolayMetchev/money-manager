package com.moneymanager.ui.background

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackgroundTasksTest {
    @Test
    fun formatElapsedTime_formatsExpectedRanges() {
        assertEquals("00:00", formatElapsedTime(0))
        assertEquals("01:05", formatElapsedTime(65_000))
        assertEquals("1:01:01", formatElapsedTime(3_661_000))
    }

    @Test
    fun backgroundTaskPanel_updatesElapsedTimeForRunningTask() =
        runMoneyManagerComposeUiTest {
            val manager = BackgroundTaskManager(CoroutineScope(Dispatchers.Main))
            var currentTimeMillis by mutableLongStateOf(5_000L)
            manager.tasks.add(
                BackgroundTask(
                    id = 1,
                    key = "import",
                    title = "Import Transactions",
                    detail = "Downloading...",
                    status = BackgroundTaskStatus.RUNNING,
                    startedAtMillis = 0L,
                ),
            )

            mainClock.autoAdvance = false
            setContent {
                BackgroundTaskPanel(
                    manager = manager,
                    currentTimeMillisProvider = { currentTimeMillis },
                )
            }

            onNodeWithText("Elapsed 00:05").assertIsDisplayed()

            runOnIdle { currentTimeMillis = 6_000L }
            mainClock.advanceTimeBy(1_000L)
            waitForIdle()

            onNodeWithText("Elapsed 00:06").assertIsDisplayed()
        }

    @Test
    fun backgroundTaskPanel_doesNotShowElapsedForCompletedTask() =
        runMoneyManagerComposeUiTest {
            val manager = BackgroundTaskManager(CoroutineScope(Dispatchers.Main))
            manager.tasks.add(
                BackgroundTask(
                    id = 1,
                    key = "download",
                    title = "Download Transactions",
                    detail = "Done",
                    status = BackgroundTaskStatus.SUCCEEDED,
                    startedAtMillis = 0L,
                ),
            )
            manager.tasks.add(
                BackgroundTask(
                    id = 2,
                    key = "import",
                    title = "Import Transactions",
                    detail = "Running",
                    status = BackgroundTaskStatus.RUNNING,
                    startedAtMillis = 0L,
                ),
            )

            setContent { BackgroundTaskPanel(manager = manager) }

            onNodeWithText("Elapsed", substring = true).assertIsDisplayed()
            onAllNodesWithText("Elapsed", substring = true).assertCountEquals(1)
        }
}
