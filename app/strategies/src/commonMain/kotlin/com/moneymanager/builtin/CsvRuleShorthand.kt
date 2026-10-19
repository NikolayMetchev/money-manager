package com.moneymanager.builtin

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr

// Shorthand for the rules the built-in strategies declare. It only builds config: everything here is
// plain StrategyRule data, editable like any user-written rule.

/** A rule doing [actions] when every one of [conditions] holds. */
internal fun rule(
    vararg actions: RuleAction,
    conditions: List<Condition> = emptyList(),
): StrategyRule = StrategyRule(conditions = conditions, actions = actions.toList())

/** [field]'s account is named by the strategy's rules; new accounts go under [defaultCategoryId]. */
internal fun accountFromRules(
    field: TransferField,
    defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
): Pair<TransferField, FieldMapping> = field to AccountFromRulesMapping(field, defaultCategoryId)

/** [namings], in order, as candidates for [side] when every one of [conditions] holds. */
internal fun accounts(
    side: AccountSide,
    vararg namings: AccountNaming,
    conditions: List<Condition> = emptyList(),
): StrategyRule = StrategyRule.accounts(side, namings.toList(), conditions)

/** A naming that reads [column] (see [AccountNaming]). */
internal fun naming(
    column: String,
    pattern: String? = null,
    name: String = AccountNaming.VALUE_PLACEHOLDER,
    trim: Boolean = false,
    fallbackName: String? = null,
    extraction: Extraction? = null,
    attributeTypeName: String? = null,
    person: Boolean = false,
    personName: String? = null,
    unidentified: Boolean = false,
): AccountNaming =
    AccountNaming(
        value = ValueExpr.of(column, extraction = extraction),
        trim = trim,
        pattern = pattern,
        name = name,
        fallbackName = fallbackName,
        attributeTypeName = attributeTypeName,
        person = person,
        personName = personName,
        unidentified = unidentified,
    )

/** A naming of the fixed account [name], applying to every row (it reads [column], which every row has). */
internal fun fixedNaming(
    column: String,
    name: String,
    unidentified: Boolean = false,
): AccountNaming = naming(column, pattern = "^", name = name, unidentified = unidentified)

/**
 * Tags the row with [column]'s value as a [typeName] attribute. With an [extraction] the attribute is the
 * extracted value, and only a value the extraction's pattern matches is tagged; with a [label] as well,
 * the attribute is that label instead. An identifying ([unique]) attribute is always the whole value.
 */
internal fun attribute(
    column: String,
    typeName: String,
    unique: Boolean = false,
    extraction: Extraction? = null,
    label: String? = null,
    conditions: List<Condition> = emptyList(),
): StrategyRule {
    val extracting = extraction?.takeUnless { unique }
    val guard =
        extracting?.let { listOf(Condition(column, ConditionOp.NOT_BLANK), Condition(column, ConditionOp.MATCHES, it.pattern)) }.orEmpty()
    val value =
        if (extracting != null && label != null) {
            RuleValue.constant(label)
        } else {
            RuleValue.Read(ValueExpr.of(column, extraction = extracting))
        }
    return rule(RuleAction.Attribute(typeName, value, unique), conditions = conditions + guard)
}

/** Excludes a row whose [column] matches [pattern], with [reason]. */
internal fun excludeWhen(
    column: String,
    pattern: String,
    reason: String,
    conditions: List<Condition> = emptyList(),
): StrategyRule =
    rule(
        RuleAction.Exclude(RuleValue.constant(reason)),
        conditions = conditions + listOf(Condition(column, ConditionOp.NOT_BLANK), Condition(column, ConditionOp.MATCHES, pattern)),
    )

/** The row's money comes into the statement account when every one of [conditions] holds. */
internal fun incomingWhen(vararg conditions: Condition): StrategyRule =
    rule(RuleAction.SetDirection(incoming = true), conditions = conditions.toList())

/** The row's money comes into the statement account when [amountColumn] is positive. */
internal fun incomingWhenPositive(amountColumn: String): StrategyRule = incomingWhen(Condition(amountColumn, ConditionOp.POSITIVE))

/** The row carries [fee] when every one of [conditions] holds. */
internal fun fee(
    fee: FeeRule,
    conditions: List<Condition> = emptyList(),
): StrategyRule = rule(RuleAction.Fee(fee), conditions = conditions)
