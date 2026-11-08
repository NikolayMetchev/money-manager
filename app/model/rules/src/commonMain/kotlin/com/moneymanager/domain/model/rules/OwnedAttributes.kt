package com.moneymanager.domain.model.rules

import com.moneymanager.domain.model.WellKnownIds

/** The attribute type a record a rule excluded carries, its value the exclusion's reason. */
const val EXCLUDED_ATTRIBUTE_TYPE_NAME = "excluded"

/**
 * The attribute types these rules write, each with the values it can take: a fixed set when every rule
 * writing it writes a constant, or null (anything) when some rule copies a value from the record. A
 * re-import removes an owned value a record no longer reports, and never touches other attributes.
 */
fun List<StrategyRule>.ownedAttributeValues(): Map<String, Set<String>?> {
    val owned = LinkedHashMap<String, MutableList<String?>>()
    for (action in asSequence().flatMap { it.actions }) {
        val (typeName, value) =
            when (action) {
                is RuleAction.Attribute -> action.typeName to action.value.constantOrNull()
                is RuleAction.Exclude -> EXCLUDED_ATTRIBUTE_TYPE_NAME to action.excludedValueOrNull()
                is RuleAction.ManualEntry -> WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME to action.name
                else -> continue
            }
        owned.getOrPut(typeName) { mutableListOf() } += value
    }
    return owned.mapValues { (_, values) -> if (values.all { it != null }) values.filterNotNull().toSet() else null }
}

/** The value an exclusion with [reason] writes: the reason, or the type's own name when it has none. */
fun excludedAttributeValue(reason: String?): String = reason?.takeIf { it.isNotBlank() } ?: EXCLUDED_ATTRIBUTE_TYPE_NAME

/** What [this] exclusion writes: a fixed value, or null (anything) when its reason is read from the record. */
private fun RuleAction.Exclude.excludedValueOrNull(): String? =
    when (val reason = reason) {
        null -> EXCLUDED_ATTRIBUTE_TYPE_NAME
        else -> reason.constantOrNull()?.let(::excludedAttributeValue)
    }

private fun RuleValue.constantOrNull(): String? = (this as? RuleValue.Template)?.template?.takeUnless { '{' in it }
