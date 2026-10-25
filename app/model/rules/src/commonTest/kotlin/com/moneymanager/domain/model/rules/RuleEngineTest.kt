package com.moneymanager.domain.model.rules

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    private val engine = RuleEngine()
    private val columns = mapOf("Type" to 0, "Amount" to 1, "From" to 2, "To" to 3, "Status" to 4)

    private fun row(vararg values: String) = ColumnRecord(values.toList(), columns)

    private fun rule(
        vararg actions: RuleAction,
        conditions: List<Condition> = emptyList(),
    ) = StrategyRule(conditions = conditions, actions = actions.toList())

    @Test
    fun `a rule applies only when all of its conditions hold`() {
        val rules = listOf(rule(RuleAction.Skip, conditions = listOf(Condition("Status", ConditionOp.EQUALS, "Failed"))))
        assertTrue(engine.evaluate(rules, row("Card", "1", "A", "B", "Failed")).skipped)
        assertFalse(engine.evaluate(rules, row("Card", "1", "A", "B", "Done")).skipped)
    }

    @Test
    fun `a rewrite is seen by the rules after it`() {
        val rules =
            listOf(
                rule(RuleAction.SwapValues("From", "To"), conditions = listOf(Condition("Type", ConditionOp.EQUALS, "In"))),
                rule(
                    RuleAction.Attribute("payer", RuleValue.read("From")),
                    conditions = listOf(Condition("From", ConditionOp.EQUALS, "B")),
                ),
            )
        val outcome = engine.evaluate(rules, row("In", "1", "A", "B", ""))
        assertEquals("B", outcome.record.text("From"))
        assertEquals("A", outcome.record.text("To"))
        assertEquals(listOf(RuleAttribute("payer", "B", uniqueIdentifier = false)), outcome.attributes)
    }

    @Test
    fun `single-valued slots keep the first claim and lists keep every claim`() {
        val first = AccountNaming(value = ValueExpr.of("From"), pattern = "^A$")
        val second = AccountNaming(value = ValueExpr.of("To"))
        val rules =
            listOf(
                rule(RuleAction.SetDirection(incoming = true), RuleAction.Account(AccountSide.SOURCE, first)),
                rule(RuleAction.SetDirection(incoming = false), RuleAction.Account(AccountSide.SOURCE, second)),
                rule(RuleAction.Exclude(RuleValue.read("Status")), RuleAction.Exclude(RuleValue.constant("later"))),
            )
        val outcome = engine.evaluate(rules, row("Card", "1", "A", "B", "Declined"))
        assertEquals(true, outcome.incoming)
        assertEquals(listOf(first, second), outcome.accountsFor(AccountSide.SOURCE))
        assertTrue(outcome.excluded)
        assertEquals("Declined", outcome.excludeReason)
        assertTrue(outcome.accountsFor(AccountSide.TARGET).isEmpty())
    }

    @Test
    fun `skip ends evaluation`() {
        val rules = listOf(rule(RuleAction.Skip, RuleAction.Flip), rule(RuleAction.Flip))
        val outcome = engine.evaluate(rules, row("Card", "1", "A", "B", ""))
        assertTrue(outcome.skipped)
        assertFalse(outcome.flipped)
    }

    @Test
    fun `two flips cancel`() {
        assertFalse(engine.evaluate(listOf(rule(RuleAction.Flip), rule(RuleAction.Flip)), row("", "", "", "", "")).flipped)
        assertTrue(engine.evaluate(listOf(rule(RuleAction.Flip)), row("", "", "", "", "")).flipped)
    }

    @Test
    fun `a template inserts record values and a blank attribute is omitted`() {
        val rules =
            listOf(
                rule(
                    RuleAction.ExternalId(RuleValue.Template("{Type}-{Amount}")),
                    RuleAction.Attribute("empty", RuleValue.read("Status")),
                    RuleAction.SetValue("Type", RuleValue.constant("Fixed")),
                ),
            )
        val outcome = engine.evaluate(rules, row("Card", "12", "A", "B", " "))
        assertEquals("Card-12", outcome.externalId)
        assertTrue(outcome.attributes.isEmpty())
        assertEquals("Fixed", outcome.record.text("Type"))
    }

    @Test
    fun `sign conditions read amounts the way statements write them`() {
        val rules = RuleEvaluator()
        assertTrue(rules.matches(Condition("Amount", ConditionOp.NEGATIVE), row("", "-1,234.50", "", "", "")))
        assertTrue(rules.matches(Condition("Amount", ConditionOp.POSITIVE), row("", "£12", "", "", "")))
        assertFalse(rules.matches(Condition("Amount", ConditionOp.POSITIVE), row("", "0", "", "", "")))
        assertFalse(rules.matches(Condition("Amount", ConditionOp.NEGATIVE), row("", "n/a", "", "", "")))
        assertFalse(rules.matches(Condition("Missing", ConditionOp.POSITIVE), row("", "1", "", "", "")))
        assertNull(parseDecimalOrNull(" "))
    }

    @Test
    fun `rules round-trip through JSON`() {
        val rules =
            listOf(
                StrategyRule(
                    name = "Card payments",
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, "Card")),
                    actions =
                        listOf(
                            RuleAction.Account(AccountSide.TARGET, AccountNaming(value = ValueExpr.of("To"), person = true)),
                            RuleAction.Leg("trade", LegRole.BySign("Amount")),
                            RuleAction.Fee(FeeRule(amount = ValueExpr.of("Fee"))),
                            RuleAction.LedgerTradeLeg,
                        ),
                ),
            )
        val json = Json.encodeToString(rules)
        assertEquals(rules, Json.decodeFromString<List<StrategyRule>>(json))
    }

    @Test
    fun `a capture sets each named group and claims its target`() {
        val rules =
            listOf(
                rule(
                    RuleAction.Capture("From", """^(?<day>\d+) (?<mon>[A-Z][a-z]+)(?: (?<note>x))?$""", CaptureTarget.NEW_ROW),
                    RuleAction.SetValue("date", RuleValue.Template("{day}-{mon}")),
                ),
                rule(RuleAction.Capture("From", """(?<other>.+)""", CaptureTarget.ROW)),
            )
        val outcome = engine.evaluate(rules, row("Card", "1", "2 Jan", "B", ""))
        assertEquals(CaptureTarget.NEW_ROW, outcome.capture)
        assertEquals(mapOf("day" to "2", "mon" to "Jan", "date" to "2-Jan", "other" to "2 Jan"), outcome.rewrites)
    }

    @Test
    fun `a capture that doesn't match stops its rule`() {
        val rules =
            listOf(
                rule(
                    RuleAction.Capture("From", """^(?<day>\d+)$""", CaptureTarget.ROW),
                    RuleAction.SetValue("date", RuleValue.constant("x")),
                ),
            )
        val outcome = engine.evaluate(rules, row("Card", "1", "Jan", "B", ""))
        assertNull(outcome.capture)
        assertTrue(outcome.rewrites.isEmpty())
    }

    @Test
    fun `capture group names come in order and once each`() {
        assertEquals(listOf("a", "b"), captureGroupNames("""(?<a>x)(?:y)(?<b>z)|(?<a>w)"""))
    }

    @Test
    fun `a capture round-trips through JSON`() {
        val rule = rule(RuleAction.Capture("text", "(?<a>.)", CaptureTarget.DOCUMENT))
        assertEquals(rule, Json.decodeFromString(StrategyRule.serializer(), Json.encodeToString(StrategyRule.serializer(), rule)))
    }
}
