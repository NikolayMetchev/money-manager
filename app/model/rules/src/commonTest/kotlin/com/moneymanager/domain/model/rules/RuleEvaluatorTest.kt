package com.moneymanager.domain.model.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEvaluatorTest {
    private val rules = RuleEvaluator()
    private val columns = mapOf("Type" to 0, "Name" to 1, "Other" to 2, "Missing cell" to 3)
    private val row = ColumnRecord(listOf(" Card ", "Tesco", "Tesco"), columns)

    private fun holds(
        op: ConditionOp,
        path: String = "Type",
        value: String? = null,
        otherPath: String? = null,
    ) = rules.matches(Condition(path, op, value, otherPath), row)

    @Test
    fun `text comparisons trim but patterns see the raw value`() {
        assertTrue(holds(ConditionOp.EQUALS, value = "Card"))
        assertTrue(holds(ConditionOp.EQUALS_IGNORE_CASE, value = "card"))
        assertTrue(holds(ConditionOp.IN, value = "Cash, Card"))
        assertFalse(holds(ConditionOp.NOT_IN, value = "Cash, Card"))
        assertFalse(holds(ConditionOp.STARTS_WITH, value = "Card"))
        assertTrue(holds(ConditionOp.MATCHES, value = "^\\s+card"))
    }

    @Test
    fun `an absent value is blank, unequal and outside every list`() {
        assertTrue(holds(ConditionOp.BLANK, path = "Missing cell"))
        assertTrue(holds(ConditionOp.BLANK, path = "No such column"))
        assertFalse(holds(ConditionOp.EQUALS, path = "No such column", value = ""))
        assertTrue(holds(ConditionOp.NOT_EQUALS, path = "No such column", value = "x"))
        assertTrue(holds(ConditionOp.NOT_IN, path = "No such column", value = "x"))
        assertFalse(holds(ConditionOp.MATCHES, path = "Missing cell", value = "^$"))
        assertTrue(holds(ConditionOp.EXISTS, path = "Missing cell"))
        assertFalse(holds(ConditionOp.EXISTS, path = "No such column"))
    }

    @Test
    fun `path comparisons compare two values of the record`() {
        assertTrue(holds(ConditionOp.EQUALS_PATH, path = "Name", otherPath = "Other"))
        assertFalse(holds(ConditionOp.NOT_EQUALS_PATH, path = "Name", otherPath = "Other"))
        assertTrue(holds(ConditionOp.NOT_EQUALS_PATH, otherPath = "Name"))
    }

    @Test
    fun `structural ops never hold for a flat row`() {
        assertFalse(holds(ConditionOp.ANY_ELEMENT_STARTS_WITH, value = "C"))
        assertFalse(holds(ConditionOp.NON_EMPTY_OBJECT))
        assertTrue(holds(ConditionOp.EMPTY_OBJECT))
    }

    @Test
    fun `a value expression takes the first non-blank path and keeps it when the extraction misses`() {
        val lookup = mapOf("A" to " ", "B" to "Paid 12.00 GBP", "C" to "x")::get
        assertEquals("Paid 12.00 GBP", rules.resolve(ValueExpr.of("A", "B", "C"), lookup))
        assertEquals("12.00", rules.resolve(ValueExpr.of("B", extraction = Extraction("(\\d+\\.\\d+)", "$1")), lookup))
        assertEquals("x", rules.resolve(ValueExpr.of("C", extraction = Extraction("(\\d+)", "$1")), lookup))
        assertEquals("", rules.resolve(ValueExpr.of("A", extraction = Extraction(".*", "never")), lookup))
        assertNull(rules.extract("abc", Extraction("\\d")))
    }

    @Test
    fun `asset codes strip a suffix then alias, case-insensitively`() {
        val codes = AssetCodeRules(aliases = mapOf("xxbt" to "btc"), stripSuffixes = setOf(".F"))
        assertEquals("BTC", codes.canonical(" xxbt.f "))
        assertEquals("ETH", codes.canonical("eth"))
        assertEquals("KNC", AssetCodeRules(aliases = mapOf("KNCL" to "KNC")).canonical("kncl"))
    }

    @Test
    fun `a blank suffix never hides a real one`() {
        assertEquals("XETH", AssetCodeRules(stripSuffixes = setOf(" ", ".F")).canonical("xeth.f"))
    }

    @Test
    fun `switching op keeps only the operand the new op uses`() {
        val condition = Condition("Type", ConditionOp.EQUALS_PATH, otherPath = "Name")
        assertEquals(Condition("Type", ConditionOp.EQUALS, value = ""), condition.withOp(ConditionOp.EQUALS))
        assertEquals(Condition("Type", ConditionOp.BLANK), condition.withOp(ConditionOp.BLANK))
        assertFalse(Condition("Type", ConditionOp.EQUALS, value = "").isComplete())
        assertTrue(Condition("Type", ConditionOp.BLANK).isComplete())
    }
}
