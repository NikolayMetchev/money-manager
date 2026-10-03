package com.moneymanager.database.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * CSV config v1 → v2: one account-rule list and one direction.
 * - The account mappings (lookup, template, regex rules with a fallback, attribute match, and the
 *   conditional choice between two of those) become an `AccountRulesMapping`/`AccountRulesExport`: an
 *   ordered rule list, a conditional's branches flattened into rules guarded by its conditions.
 * - The amount mapping's `flipAccountsOnPositive` (+ `negateValues`) becomes `direction`: the amount's
 *   sign (negated when the values were), or always outgoing.
 */
internal val csvAccountRulesStep =
    ConfigMigrationStep { config ->
        config.rewriteObjects { obj ->
            when {
                obj.isType("AmountParsingMapping", "AmountParsingExport") -> amountOntoDirection(obj)
                obj.isType(*LEGACY_ACCOUNT_MAPPINGS) -> accountMappingOntoRules(obj)
                else -> obj
            }
        } as JsonObject
    }

private val LEGACY_ACCOUNT_MAPPINGS =
    arrayOf(
        "AccountLookupMapping",
        "AccountLookupExport",
        "TemplateAccountMapping",
        "TemplateAccountExport",
        "RegexAccountMapping",
        "RegexAccountExport",
        "AttributeMatchAccountMapping",
        "AttributeMatchAccountExport",
        "ConditionalAccountMapping",
        "ConditionalAccountExport",
    )

private fun amountOntoDirection(obj: JsonObject): JsonObject {
    val flip = obj.string("flipAccountsOnPositive") == "true"
    val negate = obj.string("negateValues") == "true"
    val direction =
        if (flip) {
            JsonObject(mapOf("type" to JsonPrimitive("amountSign"), "positiveIsIncoming" to JsonPrimitive(!negate)))
        } else {
            JsonObject(mapOf("type" to JsonPrimitive("outgoing")))
        }
    return JsonObject(obj.without("flipAccountsOnPositive", "negateValues") + ("direction" to direction))
}

private const val VALUE = "{value}"

private fun accountMappingOntoRules(obj: JsonObject): JsonObject {
    val export = obj.string("type")!!.substringAfterLast('.').endsWith("Export")
    val (rules, category) = legacyAccountRules(obj)
    return JsonObject(
        buildMap {
            put("type", JsonPrimitive(if (export) "$CSV_EXPORT_PACKAGE.AccountRulesExport" else "$CSV_PACKAGE.AccountRulesMapping"))
            put("fieldType", obj.getValue("fieldType"))
            put("rules", JsonArray(rules))
            if (export) {
                put("defaultCategoryName", category ?: JsonPrimitive("Uncategorized"))
            } else {
                category?.let { put("defaultCategoryId", it) }
            }
        },
    )
}

private const val CSV_PACKAGE = "com.moneymanager.domain.model.csvstrategy"
private const val CSV_EXPORT_PACKAGE = "$CSV_PACKAGE.export"

/** The rules a legacy account mapping (or an already-converted one) amounts to, plus its category. */
private fun legacyAccountRules(obj: JsonObject): Pair<List<JsonObject>, JsonElement?> {
    val category = obj["defaultCategoryId"] ?: obj["defaultCategoryName"]
    val column = obj.string("columnName")
    return when {
        obj.isType("AccountRulesMapping", "AccountRulesExport") -> (obj["rules"] as JsonArray).map { it as JsonObject } to category
        obj.isType("AccountLookupMapping", "AccountLookupExport") ->
            listOf(rule(paths = listOf(column!!) + obj.stringList("fallbackColumns"))) to category
        obj.isType("TemplateAccountMapping", "TemplateAccountExport") ->
            listOf(
                rule(
                    paths = listOf(column!!),
                    extraction = obj["extraction"],
                    trim = true,
                    name = obj.string("prefix").orEmpty() + VALUE + obj.string("suffix").orEmpty(),
                ),
            ) to category
        obj.isType("AttributeMatchAccountMapping", "AttributeMatchAccountExport") ->
            listOf(
                rule(paths = listOf(column!!), trim = true, attributeTypeName = obj.string("attributeTypeName")),
                rule(paths = listOf(column), trim = true),
            ) to category
        obj.isType("RegexAccountMapping", "RegexAccountExport") -> {
            val patternRules =
                (obj["rules"] as? JsonArray).orEmpty().map { element ->
                    val r = element as JsonObject
                    val template = r.string("accountNameTemplate")
                    rule(
                        paths = listOf(column!!),
                        pattern = r.string("pattern"),
                        name = template ?: r.string("accountName")!!,
                        fallbackName = r.string("accountName").takeIf { template != null },
                        extras =
                            buildMap {
                                if (r.string("counterpartyIsPerson") == "true") put("counterpartyIsPerson", JsonPrimitive(true))
                                r.string("personNameTemplate")?.let { put("personName", JsonPrimitive(it)) }
                                if (r.string("counterpartyIsUnidentified") == "true") {
                                    put("counterpartyIsUnidentified", JsonPrimitive(true))
                                }
                            },
                    )
                }
            (patternRules + rule(paths = listOf(column!!) + obj.stringList("fallbackColumns"))) to category
        }
        obj.isType("ConditionalAccountMapping", "ConditionalAccountExport") -> {
            val conditions = (obj["conditions"] as? JsonArray).orEmpty()
            val (whenTrue, trueCategory) = legacyAccountRules(obj.getValue("whenTrue") as JsonObject)
            val (whenFalse, falseCategory) = legacyAccountRules(obj.getValue("whenFalse") as JsonObject)
            val guarded =
                whenTrue.map { r ->
                    JsonObject(r + ("conditions" to JsonArray(conditions + (r["conditions"] as? JsonArray).orEmpty())))
                }
            (guarded + whenFalse) to (falseCategory ?: trueCategory)
        }
        else -> error("A conditional account mapping can only branch to a column-based mapping, not ${obj.string("type")}")
    }
}

private fun rule(
    paths: List<String>,
    extraction: JsonElement? = null,
    trim: Boolean = false,
    pattern: String? = null,
    name: String = VALUE,
    fallbackName: String? = null,
    attributeTypeName: String? = null,
    extras: Map<String, JsonElement> = emptyMap(),
): JsonObject =
    JsonObject(
        buildMap {
            put("value", valueExprJson(paths, extraction))
            if (trim) put("trim", JsonPrimitive(true))
            pattern?.let { put("pattern", JsonPrimitive(it)) }
            put("name", JsonPrimitive(name))
            fallbackName?.let { put("fallbackName", JsonPrimitive(it)) }
            attributeTypeName?.let { put("attributeTypeName", JsonPrimitive(it)) }
            putAll(extras)
        },
    )
