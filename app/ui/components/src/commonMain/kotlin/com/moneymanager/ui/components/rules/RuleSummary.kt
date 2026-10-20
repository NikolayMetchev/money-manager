package com.moneymanager.ui.components.rules

import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr

private const val MISSING_INPUT = "?"

/**
 * A rule on one line, as a collapsed rule card shows it: "If Type = Card → counterparty “ATM”; skip". A
 * blank input reads as "?", so an incomplete rule still shows what it lacks.
 */
fun StrategyRule.summary(): String {
    val conditionText = if (conditions.isEmpty()) "Always" else "If " + conditions.joinToString(" and ") { it.summary() }
    val actionText = if (actions.isEmpty()) "do nothing" else actions.joinToString("; ") { it.summary() }
    return "$conditionText → $actionText"
}

private fun String?.orMissing(): String = this?.takeIf { it.isNotBlank() } ?: MISSING_INPUT

fun Condition.summary(): String {
    val field = path.orMissing()
    val operand = value.orMissing()
    return when (op) {
        ConditionOp.EQUALS -> "$field = $operand"
        ConditionOp.NOT_EQUALS -> "$field ≠ $operand"
        ConditionOp.EQUALS_IGNORE_CASE -> "$field ≈ $operand"
        ConditionOp.IN -> "$field in [$operand]"
        ConditionOp.NOT_IN -> "$field not in [$operand]"
        ConditionOp.STARTS_WITH -> "$field starts with $operand"
        ConditionOp.MATCHES -> "$field matches /$operand/"
        ConditionOp.ANY_ELEMENT_STARTS_WITH -> "$field has an element starting with $operand"
        ConditionOp.EQUALS_PATH -> "$field = ${otherPath.orMissing()}"
        ConditionOp.NOT_EQUALS_PATH -> "$field ≠ ${otherPath.orMissing()}"
        else -> "$field ${op.label()}"
    }
}

private fun ValueExpr.summary(): String = paths.joinToString(" | ") { it.orMissing() }

private fun RuleValue.summary(): String =
    when (this) {
        is RuleValue.Read -> expr.summary()
        is RuleValue.Template -> "“$template”"
    }

private fun AccountSide.summary(): String =
    when (this) {
        AccountSide.SOURCE -> "source"
        AccountSide.TARGET -> "target"
        AccountSide.COUNTERPARTY -> "counterparty"
        AccountSide.OWN -> "own account"
        AccountSide.INTERMEDIATE -> "intermediate"
    }

/** The name an account naming gives: the value it read, or the name it writes out. */
private fun AccountNaming.summary(): String =
    if (name == AccountNaming.VALUE_PLACEHOLDER) "from ${value.summary()}" else "“${name.orMissing()}”"

@Suppress("CyclomaticComplexMethod") // One phrase per action kind.
fun RuleAction.summary(): String =
    when (this) {
        is RuleAction.Account -> "${side.summary()} ${naming.summary()}"
        is RuleAction.SetDirection -> if (incoming) "money in" else "money out"
        RuleAction.Flip -> "flip source and target"
        is RuleAction.SwapValues -> "swap ${first.orMissing()} ↔ ${second.orMissing()}"
        is RuleAction.SetValue -> "set ${path.orMissing()} = ${value.summary()}"
        RuleAction.Skip -> "skip"
        is RuleAction.Exclude -> reason?.let { "exclude (reason: ${it.summary()})" } ?: "exclude"
        is RuleAction.Attribute -> "attribute ${typeName.orMissing()} = ${value.summary()}" + if (uniqueIdentifier) " (identifies)" else ""
        is RuleAction.ExternalId -> "id = ${value.summary()}"
        RuleAction.CounterpartyIsPerson -> "counterparty is a person"
        RuleAction.IgnoreCounterpartyId -> "ignore the counterparty id"
        is RuleAction.Fee -> "fee ${fee.amount.summary()}"
        is RuleAction.Foreign -> "foreign amount ${foreignAmount.amount.summary()} ${foreignAmount.currency.summary()}"
        is RuleAction.ThroughConduit -> "through “${accountName.orMissing()}”"
        is RuleAction.TradeSide -> if (buy) "buy" else "sell"
        is RuleAction.Leg -> "${role.summary()} leg of ${group.orMissing()}"
        RuleAction.LedgerTradeLeg -> "ledger trade leg"
    }

private fun LegRole.summary(): String =
    when (this) {
        LegRole.Debit -> "debit"
        LegRole.Credit -> "credit"
        is LegRole.BySign -> "by-sign (${path.orMissing()})"
    }
