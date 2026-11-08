package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.ownedAttributeValues

/** The attribute types the strategy's rules mark as identifying a row across imports, in rule order. */
fun CsvStrategyConfig<*>.uniqueIdentifierAttributeTypes(): List<String> =
    ruleActions()
        .filterIsInstance<RuleAction.Attribute>()
        .filter { it.uniqueIdentifier }
        .map { it.typeName }
        .distinct()
        .toList()

/** The attribute types the strategy's rules write (see [List.ownedAttributeValues]). */
fun CsvStrategyConfig<*>.ownedAttributeValues(): Map<String, Set<String>?> = rules.ownedAttributeValues()

private fun CsvStrategyConfig<*>.ruleActions(): Sequence<RuleAction> = rules.asSequence().flatMap { it.actions }
