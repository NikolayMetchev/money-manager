@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyRulesEditorTest {
    private val textPath =
        ConditionPathField { label, value, onValueChange, isError ->
            OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, isError = isError)
        }

    private fun runEditor(
        initial: List<StrategyRule>,
        context: RuleContext = RuleContext.CSV_ROW,
        block: ComposeUiTest.(() -> List<StrategyRule>) -> Unit,
    ) = runMoneyManagerComposeUiTest {
        var rules by mutableStateOf(initial)
        setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    StrategyRulesEditor(rules, { rules = it }, context, textPath, enabled = true)
                }
            }
        }
        block { rules }
    }

    @Test
    fun addingARuleAndAnActionBuildsTheRule() =
        runEditor(emptyList()) { rules ->
            onNodeWithText("Add rule").performClick()
            waitForIdle()
            onNodeWithText("Add action").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Skip the record").performClick()
            waitForIdle()
            assertEquals(listOf(StrategyRule(actions = listOf(RuleAction.Skip))), rules())
        }

    @Test
    fun rulesMoveDownAndAreRemoved() {
        val first = StrategyRule(name = "first", actions = listOf(RuleAction.Flip))
        val second =
            StrategyRule(
                name = "second",
                conditions = listOf(Condition("Type", ConditionOp.EQUALS, "In")),
                actions = listOf(RuleAction.Skip),
            )
        runEditor(listOf(first, second)) { rules ->
            onAllNodesWithContentDescription("Move rule down")[0].performClick()
            waitForIdle()
            assertEquals(listOf(second, first), rules())
            onAllNodesWithContentDescription("Remove rule")[0].performClick()
            waitForIdle()
            assertEquals(listOf(first), rules())
        }
    }

    @Test
    fun theActionMenuOffersOnlyTheActionsOfItsContext() =
        runEditor(listOf(StrategyRule(actions = emptyList())), context = RuleContext.API_TRADE) { _ ->
            onNodeWithText("Add action").performClick()
            waitForIdle()
            onNodeWithText("Set the trade side").assertExists()
            onNodeWithText("Flip source and target").assertDoesNotExist()
        }

    @Test
    fun `rules are complete only when every condition and action has its inputs`() {
        val payee = StrategyRule(conditions = listOf(Condition("Payee", ConditionOp.EQUALS, "Tesco")), actions = listOf(RuleAction.Skip))
        assertTrue(listOf(payee).isComplete())
        assertTrue(emptyList<StrategyRule>().isComplete())

        assertFalse(listOf(payee, payee.copy(conditions = listOf(Condition("", ConditionOp.EQUALS, "Tesco")))).isComplete())
        assertFalse(listOf(payee.copy(actions = listOf(RuleAction.Attribute("id", RuleValue.read(""))))).isComplete())
    }
}
