package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the [PredicateOp.NOT_EQUALS]/[PredicateOp.IN] item-filter operators. */
class ItemFilterPredicateTest {
    private val item = buildJsonObject { put("status", 1) }

    @Test
    fun `NOT_EQUALS matches any value other than the operand`() {
        assertTrue(item.evaluatePredicate(RulePredicate(path = "status", op = PredicateOp.NOT_EQUALS, value = "0")))
        assertFalse(item.evaluatePredicate(RulePredicate(path = "status", op = PredicateOp.NOT_EQUALS, value = "1")))
    }

    @Test
    fun `NOT_EQUALS treats an absent field as not equal`() {
        assertTrue(item.evaluatePredicate(RulePredicate(path = "missing", op = PredicateOp.NOT_EQUALS, value = "1")))
    }

    @Test
    fun `IN matches any comma-separated member`() {
        assertTrue(item.evaluatePredicate(RulePredicate(path = "status", op = PredicateOp.IN, value = "0,1,6")))
        assertFalse(item.evaluatePredicate(RulePredicate(path = "status", op = PredicateOp.IN, value = "0,2")))
    }
}
