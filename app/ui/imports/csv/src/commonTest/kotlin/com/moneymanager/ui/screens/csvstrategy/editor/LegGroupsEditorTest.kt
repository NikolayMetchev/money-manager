@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegGroupsEditorTest {
    private val anchorWhen = listOf(Condition("Parent ID", ConditionOp.BLANK))

    private fun runEditor(
        initial: List<LegGroupRule>,
        block: ComposeUiTest.(() -> List<LegGroupRule>) -> Unit,
    ) = runMoneyManagerComposeUiTest {
        var rules by mutableStateOf(initial)
        setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    LegGroupsEditor(rules, { rules = it }, emptyList(), null, enabled = true)
                }
            }
        }
        block { rules }
    }

    @Test
    fun choosingLinkedSwitchesTheAssemblyAndHidesTheLegWindow() =
        runEditor(listOf(LegGroupRule(name = "family", assembly = LegAssembly.Trade()))) { rules ->
            onNodeWithText("Window between legs of one event (seconds)").assertExists()

            // Trade, through an account, linked.
            onAllNodes(isSelectable())[2].performScrollTo().performClick()
            waitForIdle()

            assertEquals("parent", assertIs<LegAssembly.Linked>(rules().single().assembly).relationshipTypeName)
            onNodeWithText("Window between legs of one event (seconds)").assertDoesNotExist()
            onNodeWithText("Anchor row when all of these hold").assertExists()
        }

    @Test
    fun aTradeGroupCanPairItsLegsAcrossStagedFiles() =
        runEditor(listOf(LegGroupRule(name = "exchange", assembly = LegAssembly.Trade()))) { rules ->
            onNodeWithText("pair legs across files", substring = true).performScrollTo().performClick()
            waitForIdle()

            assertTrue(assertIs<LegAssembly.Trade>(rules().single().assembly).acrossFiles)
        }

    @Test
    fun aLinkedGroupEditsItsRelationshipType() =
        runEditor(listOf(LegGroupRule(name = "family", assembly = LegAssembly.Linked("parent", anchorWhen)))) { rules ->
            onAllNodesWithText("parent")[0].performScrollTo().performTextReplacement("family")
            waitForIdle()

            assertEquals(LegAssembly.Linked("family", anchorWhen), rules().single().assembly)
        }

    @Test
    fun aLinkedGroupIsCompleteOnlyWithARelationshipTypeAndAnAnchor() {
        val key = listOf(ValueExpr(listOf("Parent ID", "ID")))

        assertTrue(LegGroupRule(name = "family", key = key, assembly = LegAssembly.Linked("parent", anchorWhen)).isComplete())
        assertFalse(LegGroupRule(name = "family", key = key, assembly = LegAssembly.Linked("parent")).isComplete())
        assertFalse(LegGroupRule(name = "family", key = key, assembly = LegAssembly.Linked(" ", anchorWhen)).isComplete())
    }
}
