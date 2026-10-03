package com.moneymanager.database.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * CSV config v0 → v1: onto the shared rule vocabulary.
 * - `RowCondition` (`columnName`/`operator`/`value`/`otherColumnName`) → `Condition`
 *   (`path`/`op`/`value`/`otherPath`), wherever one appears (preprocessing, conditional accounts, fees).
 * - `contentMatchRules` (`columnName`/`pattern`) → `Condition`s with op `MATCHES`.
 * - Description and currency mappings' `columnName` (+ `fallbackColumns`, + `extraction`) → `value`.
 * - The amount mapping's `feeCurrencyColumnName` (+ `feeCurrencyExtraction`) → `feeCurrency`.
 * - `assetAliases` → `assetCodes.aliases`.
 */
internal val csvSharedRulesStep =
    ConfigMigrationStep { config ->
        val rewritten = config.rewriteObjects(::csvObjectOntoSharedRules) as JsonObject
        val contentRules =
            (rewritten["contentMatchRules"] as? JsonArray)?.map { rule ->
                val r = rule as JsonObject
                conditionJson(path = r.string("columnName")!!, op = "MATCHES", value = r.string("pattern"))
            }
        val assetCodes = assetCodesJson(rewritten["assetAliases"], null)
        JsonObject(
            buildMap {
                putAll(rewritten.without("contentMatchRules", "assetAliases"))
                if (contentRules != null) put("contentMatchRules", JsonArray(contentRules))
                if (assetCodes != null) put("assetCodes", assetCodes)
            },
        )
    }

private fun csvObjectOntoSharedRules(obj: JsonObject): JsonObject =
    when {
        "operator" in obj && "columnName" in obj -> rowConditionToCondition(obj)
        obj.isType("DirectColumnMapping", "DirectColumnExport") ->
            obj.replacingWithValueExpr(
                key = "value",
                paths = listOf(obj.string("columnName")!!) + obj.stringList("fallbackColumns"),
                extractionKey = "extraction",
                "columnName",
                "fallbackColumns",
            )
        obj.isType("CurrencyLookupMapping", "CurrencyLookupExport") ->
            obj.replacingWithValueExpr(
                key = "value",
                paths = listOf(obj.string("columnName")!!),
                extractionKey = "extraction",
                "columnName",
            )
        obj.isType("AmountParsingMapping", "AmountParsingExport") && obj.string("feeCurrencyColumnName") != null ->
            obj.replacingWithValueExpr(
                key = "feeCurrency",
                paths = listOf(obj.string("feeCurrencyColumnName")!!),
                extractionKey = "feeCurrencyExtraction",
                "feeCurrencyColumnName",
            )
        else -> obj
    }

/** This object with [removed] (and [extractionKey]) dropped and a `ValueExpr` of [paths] put at [key]. */
private fun JsonObject.replacingWithValueExpr(
    key: String,
    paths: List<String>,
    extractionKey: String,
    vararg removed: String,
): JsonObject = JsonObject(without(extractionKey, *removed) + (key to valueExprJson(paths, this[extractionKey])))

private val ROW_CONDITION_OPS =
    mapOf(
        "EQUALS_VALUE" to "EQUALS",
        "EQUALS_COLUMN" to "EQUALS_PATH",
        "NOT_EQUALS_COLUMN" to "NOT_EQUALS_PATH",
        "IS_BLANK" to "BLANK",
        "IS_NOT_BLANK" to "NOT_BLANK",
    )

private fun rowConditionToCondition(obj: JsonObject): JsonObject {
    val operator = obj.string("operator")!!
    return conditionJson(
        path = obj.string("columnName")!!,
        op = requireNotNull(ROW_CONDITION_OPS[operator]) { "Unknown row condition operator $operator" },
        value = obj.string("value"),
        otherPath = obj.string("otherColumnName"),
    )
}
