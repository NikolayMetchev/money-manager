@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class RulesTabTest {
    private fun builtIn(name: String) = BuiltInApiStrategies.builtInApiStrategies(Clock.System.now()).single { it.name == name }

    private fun rendersEveryRule(name: String) =
        runMoneyManagerComposeUiTest {
            val state = ApiStrategyEditorState(builtIn(name))
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RulesTab(state, emptyList(), emptyList(), { _, _ -> }, enabled = true)
                    }
                }
            }
            waitForIdle()
            val lists = state.config.ruleLists()
            lists.forEach { onNodeWithText(it.title).assertExists() }
            assertEquals(lists.sumOf { it.rules.size }, onAllNodesWithContentDescription("Remove rule").fetchSemanticsNodes().size)
            assertEquals(lists.size, onAllNodesWithText("Add rule").fetchSemanticsNodes().size)
        }

    @Test
    fun monzoShowsItsAccountAndTransactionRules() = rendersEveryRule("Monzo")

    @Test
    fun krakenShowsEachDataEndpointsRules() = rendersEveryRule("Kraken")
}
