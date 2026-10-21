package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.ui.components.rules.RuleContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class ApiRuleListsTest {
    private val builtIns = BuiltInApiStrategies.builtInApiStrategies(Clock.System.now())

    private fun builtIn(name: String) = builtIns.single { it.name == name }

    /** Every rule the config holds, read straight off it rather than through [ruleLists]. */
    private fun ApiStrategyConfig.allRules(): List<StrategyRule> =
        (accounts as? ApiAccountsSource.Downloaded)?.mappings?.rules.orEmpty() +
            dataEndpoints.flatMap { it.transactionMappings?.rules.orEmpty() + it.tradeMappings?.rules.orEmpty() }

    @Test
    fun `the rules tab shows every rule of every built-in API strategy`() {
        for (strategy in builtIns) {
            val shown = strategy.config.ruleLists().flatMap { it.rules }
            assertEquals(strategy.config.allRules(), shown, strategy.name)
        }
    }

    @Test
    fun `a bank strategy lists its accounts and its transaction feed`() {
        val lists = builtIn("Monzo").config.ruleLists()
        assertEquals(listOf(RuleContext.API_ACCOUNT, RuleContext.API_TRANSACTION), lists.map { it.context })
        assertEquals(listOf(3, 10), lists.map { it.rules.size })
    }

    @Test
    fun `an exchange numbers its data endpoints as the endpoints tab does`() {
        val config = builtIn("Kraken").config
        val lists = config.ruleLists()
        assertEquals(config.dataEndpoints.size, lists.size)
        lists.forEachIndexed { i, list -> assertTrue(list.title.startsWith("${i + 1}. "), list.title) }
        assertTrue(RuleContext.API_TRADE in lists.map { it.context })
    }

    @Test
    fun `editing one list changes only its endpoint`() {
        val config = builtIn("Binance").config
        val target = config.ruleLists()[1]
        val edited = target.withRules(config, target.rules + StrategyRule(actions = listOf(RuleAction.Skip)))
        val before = config.ruleLists().map { it.rules }
        val after = edited.ruleLists().map { it.rules }
        assertEquals(before.size, after.size)
        after.forEachIndexed { i, rules ->
            assertEquals(
                if (i ==
                    1
                ) {
                    before[1] + StrategyRule(actions = listOf(RuleAction.Skip))
                } else {
                    before[i]
                },
                rules,
            )
        }
    }

    @Test
    fun `an incomplete rule flags the rules tab and not the endpoints tab`() {
        val state = ApiStrategyEditorState(builtIn("Kraken"))
        assertFalse(state.tabHasError(EditorTab.RULES))
        val list = state.config.ruleLists().last()
        val incomplete = StrategyRule(conditions = listOf(Condition("", ConditionOp.EQUALS, "x")), actions = listOf(RuleAction.Skip))
        state.updateConfig { list.withRules(this, list.rules + incomplete) }
        assertTrue(state.tabHasError(EditorTab.RULES))
        assertFalse(state.tabHasError(EditorTab.ENDPOINTS))
        assertFalse(state.isValid)
    }
}
