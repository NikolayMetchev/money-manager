@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test

class ApiReimportProgressTest {
    @Test
    fun `a counted run shows its phase and how far it is`() =
        runMoneyManagerComposeUiTest {
            setContent { MaterialTheme { ApiReimportProgress(Progress.counted("Re-importing sessions", done = 2, total = 5)) } }

            onNodeWithText("Re-importing sessions…").assertIsDisplayed()
            onNodeWithText("2 of 5").assertIsDisplayed()
        }

    @Test
    fun `a run that has not counted its work shows only its phase`() =
        runMoneyManagerComposeUiTest {
            setContent { MaterialTheme { ApiReimportProgress(Progress("Starting re-import")) } }

            onNodeWithText("Starting re-import…").assertIsDisplayed()
            onNodeWithText(" of ", substring = true).assertDoesNotExist()
        }
}
