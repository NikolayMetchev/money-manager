@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.rules

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementWindowEditorTest {
    @Test
    fun switchingKindsKeepsTheWindowsLengthAndTypingChangesIt() =
        runMoneyManagerComposeUiTest {
            var window: SettlementWindow by mutableStateOf(SettlementWindow.Elapsed(3 * 86_400L))
            setContent {
                MaterialTheme {
                    SettlementWindowEditor(window, { window = it }, label = "Placeholder window", enabled = true)
                }
            }
            onNodeWithText("within 3 days").assertExists()

            onNodeWithText("Business days").performClick()
            waitForIdle()
            assertEquals(SettlementWindow.BusinessDays(3), window)
            onNodeWithText("within 3 business days of the accounts' locations").assertExists()

            onNodeWithText("3").performTextReplacement("1")
            waitForIdle()
            assertEquals(SettlementWindow.BusinessDays(1), window)
            onNodeWithText("within 1 business day of the accounts' locations").assertExists()

            onNodeWithText("Clock time").performClick()
            waitForIdle()
            assertEquals(SettlementWindow.Elapsed(86_400L), window)

            onNodeWithText("86400").performTextReplacement("90")
            waitForIdle()
            assertEquals(SettlementWindow.Elapsed(90L), window)
            onNodeWithText("within 90 seconds").assertExists()
            onNodeWithText("90").performTextReplacement("120")
            waitForIdle()
            onNodeWithText("within 2 minutes").assertExists()
            onNodeWithText("120").performTextReplacement("7200")
            waitForIdle()
            onNodeWithText("within 2 hours").assertExists()
            // Not a number: the window keeps its last value.
            onNodeWithText("7200").performTextReplacement("x")
            waitForIdle()
            assertEquals(SettlementWindow.Elapsed(7200L), window)
        }
}
