@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.background

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackgroundTasksTest {
    @Test
    fun formatElapsedTime_formatsExpectedRanges() {
        assertEquals("00:00", formatElapsedTime(0.milliseconds))
        assertEquals("01:05", formatElapsedTime(65.seconds))
        assertEquals("1:01:01", formatElapsedTime(3661.seconds))
    }

    @Test
    fun backgroundTaskPanel_showsElapsedTimeForRunningTask() {
        runMoneyManagerComposeUiTest {
            val manager = BackgroundTaskManager(CoroutineScope(Dispatchers.Main))
            manager.tasks.add(
                BackgroundTask(
                    id = 1,
                    key = "import",
                    title = "Import Transactions",
                    detail = "Downloading...",
                    status = BackgroundTaskStatus.RUNNING,
                ),
            )

            setContent { BackgroundTaskPanel(manager = manager) }

            onNodeWithText("Elapsed", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun backgroundTaskPanel_doesNotShowElapsedForCompletedTask() {
        runMoneyManagerComposeUiTest {
            val manager = BackgroundTaskManager(CoroutineScope(Dispatchers.Main))
            manager.tasks.add(
                BackgroundTask(
                    id = 1,
                    key = "download",
                    title = "Download Transactions",
                    detail = "Done",
                    status = BackgroundTaskStatus.SUCCEEDED,
                ),
            )
            manager.tasks.add(
                BackgroundTask(
                    id = 2,
                    key = "import",
                    title = "Import Transactions",
                    detail = "Running",
                    status = BackgroundTaskStatus.RUNNING,
                ),
            )

            setContent { BackgroundTaskPanel(manager = manager) }

            onNodeWithText("Elapsed", substring = true).assertIsDisplayed()
            onAllNodesWithText("Elapsed", substring = true).assertCountEquals(1)
        }
    }

    @Test
    fun startTask_setsStartedAtMillisWhenTaskStarts() {
        val scope = CoroutineScope(Dispatchers.Default)
        val manager = BackgroundTaskManager(scope)
        val beforeStartMillis = System.currentTimeMillis()

        manager.startTask(
            key = "import",
            title = "Import",
            initialDetail = "Starting",
        ) {
            "Done"
        }
        val afterStartMillis = System.currentTimeMillis()

        assertTrue(manager.tasks.isNotEmpty())
        val startedAtMillis = manager.tasks.first().startedAtMillis
        assertTrue(startedAtMillis in beforeStartMillis..afterStartMillis)

        scope.cancel()
    }
}
