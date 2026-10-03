package com.moneymanager.apiimporter

import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the [ConditionOp.NOT_EQUALS]/[ConditionOp.IN] item-filter operators. */
class ItemFilterPredicateTest {
    private val item = buildJsonObject { put("status", 1) }

    @Test
    fun `NOT_EQUALS matches any value other than the operand`() {
        assertTrue(item.matches(Condition("status", ConditionOp.NOT_EQUALS, value = "0")))
        assertFalse(item.matches(Condition("status", ConditionOp.NOT_EQUALS, value = "1")))
    }

    @Test
    fun `NOT_EQUALS treats an absent field as not equal`() {
        assertTrue(item.matches(Condition("missing", ConditionOp.NOT_EQUALS, value = "1")))
    }

    @Test
    fun `IN matches any comma-separated member`() {
        assertTrue(item.matches(Condition("status", ConditionOp.IN, value = "0,1,6")))
        assertFalse(item.matches(Condition("status", ConditionOp.IN, value = "0,2")))
    }

    @Test
    fun `structural ops follow array-indexed paths like every other op`() {
        val nested = Json.parseToJsonElement("""{"legs": [{"id": "a", "tags": ["x"], "meta": {"k": 1}}]}""").jsonObject
        assertTrue(nested.matches(Condition("legs[0].id", ConditionOp.EXISTS)))
        assertFalse(nested.matches(Condition("legs[1].id", ConditionOp.EXISTS)))
        assertTrue(nested.matches(Condition("legs[0].tags", ConditionOp.ANY_ELEMENT_STARTS_WITH, value = "x")))
        assertTrue(nested.matches(Condition("legs[0].meta", ConditionOp.NON_EMPTY_OBJECT)))
    }
}
