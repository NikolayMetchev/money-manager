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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
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
    fun anAddedAccountActionNamesTheSideOfItsContext() =
        runEditor(listOf(StrategyRule(actions = emptyList())), context = RuleContext.API_ACCOUNT) { rules ->
            onNodeWithText("Add action").performClick()
            waitForIdle()
            onNodeWithText("Name an account").performClick()
            waitForIdle()
            assertEquals(AccountSide.OWN, (rules().single().actions.single() as RuleAction.Account).side)
        }

    @Test
    fun anApiTransactionCounterpartyCanBeMarkedBuiltIn() {
        val atm =
            StrategyRule(actions = listOf(RuleAction.Account(AccountSide.COUNTERPARTY, AccountNaming(ValueExpr.of("id"), name = "ATM"))))
        runEditor(listOf(atm), context = RuleContext.API_TRANSACTION) { rules ->
            onNodeWithContentDescription("Expand rule").performClick()
            waitForIdle()
            onNodeWithText("Built-in counterparty: every record naming it shares one account").performScrollTo().performClick()
            waitForIdle()
            assertTrue((rules().single().actions.single() as RuleAction.Account).naming.builtIn)
        }
    }

    @Test
    fun `rules are complete only when every condition and action has its inputs`() {
        val payee = StrategyRule(conditions = listOf(Condition("Payee", ConditionOp.EQUALS, "Tesco")), actions = listOf(RuleAction.Skip))
        assertTrue(listOf(payee).isComplete())
        assertTrue(emptyList<StrategyRule>().isComplete())

        assertFalse(listOf(payee, payee.copy(conditions = listOf(Condition("", ConditionOp.EQUALS, "Tesco")))).isComplete())
        assertFalse(listOf(payee.copy(actions = listOf(RuleAction.Attribute("id", RuleValue.read(""))))).isComplete())
    }

    private val atmRule =
        StrategyRule(
            conditions = listOf(Condition("Type", ConditionOp.EQUALS, "ATM")),
            actions = listOf(RuleAction.Account(AccountSide.COUNTERPARTY, AccountNaming(ValueExpr.of("Type"), name = "ATM"))),
        )

    @Test
    fun `a rule summarises its conditions and actions on one line`() {
        assertEquals("If Type = ATM → counterparty “ATM”", atmRule.summary())
        assertEquals("Always → do nothing", StrategyRule(actions = emptyList()).summary())
        assertEquals(
            "If Amount is a negative number and Kind in [a b] → money out; skip; fee Fee | Fees",
            StrategyRule(
                conditions = listOf(Condition("Amount", ConditionOp.NEGATIVE), Condition("Kind", ConditionOp.IN, "a b")),
                actions =
                    listOf(
                        RuleAction.SetDirection(incoming = false),
                        RuleAction.Skip,
                        RuleAction.Fee(FeeRule(amount = ValueExpr.of("Fee", "Fees"))),
                    ),
            ).summary(),
        )
        assertEquals(
            "Always → counterparty from Merchant; attribute ? = id (identifies); debit leg of trade",
            StrategyRule(
                actions =
                    listOf(
                        RuleAction.Account(AccountSide.COUNTERPARTY, AccountNaming(ValueExpr.of("Merchant"))),
                        RuleAction.Attribute("", RuleValue.read("id"), uniqueIdentifier = true),
                        RuleAction.Leg("trade", LegRole.Debit),
                    ),
            ).summary(),
        )
    }

    @Test
    fun aCompleteRuleShowsItsSummaryUntilExpanded() =
        runEditor(listOf(atmRule)) { _ ->
            onNodeWithText("If Type = ATM → counterparty “ATM”").assertExists()
            onNodeWithText("Add action").assertDoesNotExist()
            onNodeWithContentDescription("Expand rule").performClick()
            waitForIdle()
            onNodeWithText("Add action").assertExists()
            onNodeWithContentDescription("Collapse rule").performClick()
            waitForIdle()
            onNodeWithText("Add action").assertDoesNotExist()
        }

    @Test
    fun anIncompleteRuleStaysOpen() =
        runEditor(listOf(atmRule.copy(conditions = listOf(Condition("", ConditionOp.EQUALS, "ATM"))))) { _ ->
            onNodeWithText("Add action").assertExists()
            onNodeWithContentDescription("Collapse rule").assertIsNotEnabled()
        }

    @Test
    fun expandAllOpensEveryRuleAndAMovedRuleStaysOpen() {
        val skip = StrategyRule(name = "skip", actions = listOf(RuleAction.Skip))
        runEditor(listOf(atmRule, skip)) { rules ->
            onNodeWithText("Expand all").performClick()
            waitForIdle()
            assertEquals(2, onAllNodesWithContentDescription("Collapse rule").fetchSemanticsNodes().size)
            onAllNodesWithContentDescription("Collapse rule")[0].performClick()
            waitForIdle()
            onAllNodesWithContentDescription("Move rule down")[0].performClick()
            waitForIdle()
            assertEquals(listOf(skip, atmRule), rules())
            // The open skip rule moved to the top with its card still open; the collapsed ATM rule followed it.
            onAllNodesWithContentDescription("Collapse rule")[0].assertExists()
            onAllNodesWithContentDescription("Expand rule")[0].assertExists()
            onNodeWithText("If Type = ATM → counterparty “ATM”").assertExists()
        }
    }
}
