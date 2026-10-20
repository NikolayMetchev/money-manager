@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionTransactionRulesEditorTest {
    private val accrual =
        CompanionTransactionRule(
            name = "Accrual",
            matchAttributeName = "Transaction Type",
            matchValuePattern = "ACCRUAL_CHARGE-%",
            linkAttributeName = "Reference",
            companionDescription = "Interest",
        )

    @Test
    fun editingRemovingAndAddingRulesReportsTheNewList() {
        runMoneyManagerComposeUiTest {
            var rules by mutableStateOf(listOf(accrual))
            setContent {
                MaterialTheme {
                    CompanionTransactionRulesEditor(rules = rules, onRulesChanged = { rules = it }, enabled = true)
                }
            }

            onNode(hasSetTextAction() and hasText("Accrual")).performTextReplacement("Accrued interest")
            waitForIdle()
            assertEquals(listOf(accrual.copy(name = "Accrued interest")), rules)

            onNode(hasSetTextAction() and hasText("Interest")).performTextReplacement("Monthly interest")
            waitForIdle()
            assertEquals("Monthly interest", rules.single().companionDescription)

            onNodeWithContentDescription("Remove rule").performClick()
            waitForIdle()
            assertEquals(emptyList(), rules)

            onNodeWithText("Add Rule").performClick()
            waitForIdle()
            assertEquals(listOf(CompanionTransactionRule("", "", "", "", "")), rules)
            onNodeWithText("Rule 1").assertExists()
        }
    }
}
