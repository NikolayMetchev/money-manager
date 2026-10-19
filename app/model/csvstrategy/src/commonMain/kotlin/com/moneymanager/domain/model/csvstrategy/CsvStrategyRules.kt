package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue

/** The attribute type a row a rule excluded carries, its value the exclusion's reason. */
const val EXCLUDED_ATTRIBUTE_TYPE_NAME = "excluded"

/** The attribute types the strategy's rules mark as identifying a row across imports, in rule order. */
fun CsvStrategyConfig<*>.uniqueIdentifierAttributeTypes(): List<String> =
    ruleActions()
        .filterIsInstance<RuleAction.Attribute>()
        .filter { it.uniqueIdentifier }
        .map { it.typeName }
        .distinct()
        .toList()

/**
 * The attribute types the strategy's rules write, each with the values it can take: a fixed set when
 * every rule writing it writes a constant, or null (anything) when some rule copies a value from the
 * row. A re-import removes an owned value a row no longer reports, and never touches other attributes.
 */
fun CsvStrategyConfig<*>.ownedAttributeValues(): Map<String, Set<String>?> {
    val owned = LinkedHashMap<String, MutableList<String?>>()
    for (action in ruleActions()) {
        when (action) {
            is RuleAction.Attribute -> owned.getOrPut(action.typeName) { mutableListOf() } += action.value.constantOrNull()
            is RuleAction.Exclude ->
                owned.getOrPut(EXCLUDED_ATTRIBUTE_TYPE_NAME) { mutableListOf() } +=
                    action.reason?.constantOrNull() ?: EXCLUDED_ATTRIBUTE_TYPE_NAME
            else -> Unit
        }
    }
    return owned.mapValues { (_, values) -> if (values.all { it != null }) values.filterNotNull().toSet() else null }
}

/** The value an exclusion with [reason] writes: the reason, or the type's own name when it has none. */
fun excludedAttributeValue(reason: String?): String = reason?.takeIf { it.isNotBlank() } ?: EXCLUDED_ATTRIBUTE_TYPE_NAME

private fun CsvStrategyConfig<*>.ruleActions(): Sequence<RuleAction> = rules.asSequence().flatMap { it.actions }

private fun RuleValue.constantOrNull(): String? = (this as? RuleValue.Template)?.template?.takeUnless { '{' in it }
