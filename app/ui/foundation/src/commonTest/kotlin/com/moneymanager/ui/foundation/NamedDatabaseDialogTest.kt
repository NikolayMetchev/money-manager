@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.foundation

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamedDatabaseDialogTest {
    private var result: String? = null
    private var answered = false

    private fun ComposeUiTest.show(
        mode: DatabasePickerMode,
        names: List<String> = listOf("work.db", "home.db", "notes.txt"),
    ) {
        setContent {
            NamedDatabaseDialog(mode, names) {
                result = it
                answered = true
            }
        }
    }

    private fun ComposeUiTest.typeName(name: String) {
        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput(name)
    }

    @Test
    fun `open lists only databases and returns the one picked`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.OPEN)

            onNodeWithText("Open database").assertExists()
            onNodeWithText("notes.txt").assertDoesNotExist()
            onNodeWithText("home.db").performClick()

            assertEquals("home.db", result)
        }

    @Test
    fun `open says so when there are no databases`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.OPEN, names = emptyList())

            onNodeWithText("No databases found.").assertExists()
            onNodeWithText("Cancel").performClick()

            assertEquals(true, answered)
            assertNull(result)
        }

    @Test
    fun `create gives a new name the db extension`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.CREATE)

            typeName("  budget  ")
            onNodeWithText("Create").performClick()

            assertEquals("budget.db", result)
        }

    @Test
    fun `create keeps an extension already typed and refuses a blank name`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.CREATE)

            typeName(" ")
            onNodeWithText("Create").assertIsNotEnabled()
            typeName("budget.db")
            onNodeWithText("Create").performClick()

            assertEquals("budget.db", result)
        }

    @Test
    fun `open or create opens an existing database`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.OPEN_OR_CREATE)

            onNodeWithText("Open existing:").assertExists()
            onNodeWithText("work.db").performClick()

            assertEquals("work.db", result)
        }

    @Test
    fun `open or create creates the default database when there are none`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.OPEN_OR_CREATE, names = emptyList())

            onNodeWithText("Open existing:").assertDoesNotExist()
            onNodeWithText("Create").performClick()

            assertEquals("money_manager.db", result)
        }

    @Test
    fun `open or create can be cancelled`() =
        runMoneyManagerComposeUiTest {
            show(DatabasePickerMode.OPEN_OR_CREATE)

            onNodeWithText("Cancel").performClick()

            assertEquals(true, answered)
            assertNull(result)
        }
}
