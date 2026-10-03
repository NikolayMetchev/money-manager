package com.moneymanager.database.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * API config v0 → v1: onto the shared rule vocabulary.
 * - `RulePredicate` (`path`/`op`/`value`) → `Condition`, wherever one appears (built-in counterparty and
 *   account-name rules, item filters).
 * - Transaction mappings' `excludeField` + `excludeValues` → `excludeWhen` (an `IN` condition), and
 *   `declineReasonField` / `declineStatusField` + `declinedStatusValues` → `declinedWhen` (`NOT_BLANK`,
 *   then `IN`, in that order — the order the reason was resolved in). Both old value sets were sets, so
 *   their members are joined sorted, whatever order the JSON listed them in.
 * - `assetAliases` + `assetSuffixesToStrip` → `assetCodes`.
 */
internal val apiSharedRulesStep =
    ConfigMigrationStep { config ->
        val rewritten =
            config.rewriteObjects { obj ->
                when {
                    obj.isRulePredicate() -> predicateToCondition(obj)
                    "amountField" in obj -> transactionMappingsOntoConditions(obj)
                    else -> obj
                }
            } as JsonObject
        val assetCodes = assetCodesJson(rewritten["assetAliases"], rewritten["assetSuffixesToStrip"])
        JsonObject(
            buildMap {
                putAll(rewritten.without("assetAliases", "assetSuffixesToStrip"))
                if (assetCodes != null) put("assetCodes", assetCodes)
            },
        )
    }

private val PREDICATE_OPS =
    mapOf(
        "EXISTS" to "EXISTS",
        "EQUALS" to "EQUALS",
        "EQUALS_IGNORE_CASE" to "EQUALS_IGNORE_CASE",
        "STARTS_WITH" to "STARTS_WITH",
        "NOT_EQUALS" to "NOT_EQUALS",
        "IN" to "IN",
        "ARRAY_ANY_STARTS_WITH" to "ANY_ELEMENT_STARTS_WITH",
        "OBJECT_EMPTY" to "EMPTY_OBJECT",
        "OBJECT_NON_EMPTY" to "NON_EMPTY_OBJECT",
    )

// A RulePredicate is exactly {path, op, value}; nothing else in an API config has an `op`.
private fun JsonObject.isRulePredicate(): Boolean = "op" in this && "path" in this && keys.all { it in setOf("path", "op", "value") }

private fun predicateToCondition(obj: JsonObject): JsonObject {
    val op = obj.string("op")!!
    return conditionJson(
        path = obj.string("path")!!,
        op = requireNotNull(PREDICATE_OPS[op]) { "Unknown predicate op $op" },
        value = obj.string("value"),
    )
}

private fun transactionMappingsOntoConditions(obj: JsonObject): JsonObject {
    val excludeField = obj.string("excludeField")
    val exclude =
        if (excludeField == null) {
            emptyList()
        } else {
            listOf(conditionJson(path = excludeField, op = "IN", value = obj.stringList("excludeValues").sorted().joinToString(",")))
        }
    val declined =
        buildList {
            obj.string("declineReasonField")?.let { add(conditionJson(path = it, op = "NOT_BLANK")) }
            obj.string("declineStatusField")?.let { field ->
                val values = obj.stringList("declinedStatusValues").sorted()
                if (values.isNotEmpty()) add(conditionJson(path = field, op = "IN", value = values.joinToString(",")))
            }
        }
    return JsonObject(
        obj.without("excludeField", "excludeValues", "declineReasonField", "declineStatusField", "declinedStatusValues") +
            mapOf("excludeWhen" to JsonArray(exclude), "declinedWhen" to JsonArray(declined)),
    )
}
