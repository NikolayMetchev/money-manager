package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvStrategyRulesTest {
    private fun excludedValues(vararg reasons: RuleValue?): Set<String>? =
        CsvStrategyConfig<Nothing>(emptySet(), emptyMap(), rules = listOf(StrategyRule(actions = reasons.map { RuleAction.Exclude(it) })))
            .ownedAttributeValues()[EXCLUDED_ATTRIBUTE_TYPE_NAME]

    @Test
    fun `an exclusion owns the value it writes`() {
        assertEquals(setOf(EXCLUDED_ATTRIBUTE_TYPE_NAME), excludedValues(null))
        assertEquals(setOf("Declined"), excludedValues(RuleValue.constant("Declined")))
        assertEquals(setOf(EXCLUDED_ATTRIBUTE_TYPE_NAME), excludedValues(RuleValue.constant("")), "a blank reason writes the type's name")
    }

    @Test
    fun `an exclusion whose reason comes from the row owns any value`() {
        // Otherwise a re-import could never remove the reason it wrote from a row the rule no longer excludes.
        assertNull(excludedValues(RuleValue.read("Status")))
        assertNull(excludedValues(null, RuleValue.constant("Declined {Status}")))
    }
}
