@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.tasks.TaskManager
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test

class TaskPanelTest {
    @Test
    fun taskPanel_showsElapsedTimeOnlyForRunningTasks() {
        runMoneyManagerComposeUiTest {
            val scope = CoroutineScope(Dispatchers.Default)
            val manager = TaskManager(scope)
            val release = CompletableDeferred<Unit>()
            manager.start("download", "Download Transactions", "Starting") { "Done" }
            waitUntil { !manager.isRunning("download") }
            manager.start("import", "Import Transactions", "Downloading...") { release.await().let { "Done" } }

            setContent { TaskPanel(manager = manager) }

            waitUntilExactlyOneExists(hasText("Elapsed", substring = true))
            onAllNodesWithText("Elapsed", substring = true).assertCountEquals(1)
            onNodeWithText("Took", substring = true).assertIsDisplayed()
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun taskPanel_showsRunningChildrenUnderTheirParent() {
        runMoneyManagerComposeUiTest {
            val scope = CoroutineScope(Dispatchers.Default)
            val manager = TaskManager(scope)
            val release = CompletableDeferred<Unit>()
            manager.start("all", "Download all", "Downloading 2 folders") {
                startChild("dir-1", "Folder One", "Listing files") { release.await().let { "Done" } }
                startChild("dir-2", "Folder Two", "Listing files") { "Done" }
                "All done"
            }

            setContent { TaskPanel(manager = manager) }

            waitUntilExactlyOneExists(hasText("1 of 2 done"))
            onNodeWithText("Folder One").assertIsDisplayed()
            onAllNodesWithText("Folder Two").assertCountEquals(0)
            release.complete(Unit)
            scope.cancel()
        }
    }
}
