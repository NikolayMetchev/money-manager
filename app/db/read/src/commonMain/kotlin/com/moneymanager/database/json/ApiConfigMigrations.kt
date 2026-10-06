package com.moneymanager.database.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * API config v1 → v2: one config shape for bank and exchange strategies.
 * - `authType` is dropped: a strategy with `requestSigning` signs, any other sends a bearer token.
 * - `accounts`: `syntheticAccount` becomes `single`; otherwise `accountsEndpoint` + `accountMappings`
 *   (+ `accountIdentifiersEndpoint`, `ancestorEndpoints`) become `downloaded`. An exchange's unused
 *   placeholder accounts/transactions endpoints and mappings are dropped.
 * - A bank strategy's `transactionsEndpoint` + `transactionMappings` become its `BANK_TRANSACTIONS` data
 *   endpoint.
 * - Pagination's flat `mode` + fields become an optional `window` times a `paging` scheme. The old modes
 *   meant different things to the two download paths, which this resolves per endpoint:
 *   - the bank feed walked any non-windowed mode as a before-cursor (always sending the page size), and
 *     sent its window bounds as ISO-8601 whatever `windowBoundFormat` said, never offset/token paging;
 *   - every other endpoint paged a window (or the single request) by offset if one was set, else by a
 *     next-page token, else not at all; `FORWARD_ID_CURSOR` and `TOKEN_CURSOR` walks are unwindowed.
 *   The page size is now sent whenever `sendLimitParam` is set, so it's cleared where nothing paged.
 * - Trade mappings that read both assets from explicit fields drop their placeholder `instrumentField`.
 */
internal val apiOnePipelineStep =
    ConfigMigrationStep { config ->
        val synthetic = config["syntheticAccount"] as? JsonObject
        val bankFeed =
            (config["transactionsEndpoint"] as? JsonObject)
                ?.takeIf { synthetic == null }
                ?.let { endpoint -> endpoint.withPagination { paginationToV2(it, bankFeed = true) } }
        val accounts =
            if (synthetic != null) {
                JsonObject(mapOf("type" to JsonPrimitive("single")) + synthetic)
            } else {
                JsonObject(
                    buildMap {
                        put("type", JsonPrimitive("downloaded"))
                        put("endpoint", config.getValue("accountsEndpoint"))
                        config["accountMappings"]?.let { put("mappings", it) }
                        config["accountIdentifiersEndpoint"]?.takeUnless { it is JsonNull }?.let { put("identifiersEndpoint", it) }
                        config["ancestorEndpoints"]?.let { put("ancestorEndpoints", it) }
                    },
                )
            }
        val dataEndpoints =
            (config["dataEndpoints"] as? JsonArray).orEmpty() +
                listOfNotNull(
                    bankFeed?.let { endpoint ->
                        JsonObject(
                            mapOf(
                                "endpoint" to endpoint,
                                "kind" to JsonPrimitive("BANK_TRANSACTIONS"),
                                "transactionMappings" to config.getValue("transactionMappings"),
                            ),
                        )
                    },
                )
        val reshaped =
            JsonObject(
                config.without(
                    "authType",
                    "syntheticAccount",
                    "accountsEndpoint",
                    "accountMappings",
                    "accountIdentifiersEndpoint",
                    "ancestorEndpoints",
                    "transactionsEndpoint",
                    "transactionMappings",
                    "dataEndpoints",
                ) + mapOf("accounts" to accounts, "dataEndpoints" to JsonArray(dataEndpoints)),
            )
        // Every other endpoint's pagination; the bank feed's, already converted above, is left alone.
        reshaped.rewriteObjects { obj ->
            when {
                "path" in obj && "responseArrayKey" in obj -> obj.withPagination { paginationToV2(it, bankFeed = false) }
                // Trade mappings that read their assets from explicit fields carried a placeholder symbol path.
                obj.string("splitMode") == "EXPLICIT_FIELDS" -> JsonObject(obj.without("instrumentField"))
                else -> obj
            }
        } as JsonObject
    }

private fun JsonObject.withPagination(convert: (JsonObject) -> JsonObject): JsonObject {
    val pagination = this["pagination"] as? JsonObject ?: return this
    if ("paging" in pagination || "window" in pagination) return this
    return JsonObject(this + ("pagination" to convert(pagination)))
}

private const val DEFAULT_PAGE_SIZE = 100

private fun paginationToV2(
    old: JsonObject,
    bankFeed: Boolean,
): JsonObject {
    val mode = old.string("mode") ?: "CURSOR"
    val limitValue = old["limitValue"]?.let { (it as JsonPrimitive).content.toInt() } ?: DEFAULT_PAGE_SIZE
    val cursorParam = old.string("cursorParam") ?: "before"
    val cursorResponseField = old.string("cursorResponseField") ?: "created"
    val offsetParam = old.string("offsetParam")?.takeIf { limitValue > 0 }
    val nextCursorField = old.string("nextCursorField")
    val window =
        if (mode != "DATE_WINDOW") {
            null
        } else {
            JsonObject(
                buildMap {
                    old["startParam"]?.let { put("startParam", it) }
                    old["endParam"]?.let { put("endParam", it) }
                    old["windowDays"]?.let { put("windowDays", it) }
                    old["lookbackDays"]?.let { put("lookbackDays", it) }
                    val boundFormat = if (bankFeed) JsonPrimitive("ISO_8601") else old["windowBoundFormat"]
                    boundFormat?.let { put("boundFormat", it) }
                    old["windowRangeErrorSubstrings"]?.let { put("rangeErrorSubstrings", it) }
                },
            )
        }
    val paging: JsonObject =
        when {
            bankFeed && mode == "DATE_WINDOW" -> pagingJson("single")
            bankFeed -> pagingJson("before", "param" to cursorParam, "positionField" to cursorResponseField)
            mode == "FORWARD_ID_CURSOR" -> pagingJson("forwardId", "param" to cursorParam, "idField" to cursorResponseField)
            offsetParam != null ->
                pagingJson(
                    "offset",
                    "param" to offsetParam,
                    "pageNumbers" to (old.string("offsetMode") == "PAGE_NUMBER"),
                    "totalCountField" to old.string("totalCountField"),
                )
            nextCursorField != null ->
                pagingJson(
                    "token",
                    "tokenField" to nextCursorField,
                    "param" to cursorParam,
                    "urlEncoded" to (old.string("nextCursorUrlEncoded") == "true"),
                    "positionField" to cursorResponseField.takeIf { mode == "TOKEN_CURSOR" },
                )
            else -> pagingJson("single")
        }
    val pages = paging.string("type") != "single"
    val sendLimit = bankFeed && pages || pages && old.string("sendLimitParam") == "true"
    return JsonObject(
        buildMap {
            if (window != null) put("window", window)
            put("paging", paging)
            old["limitParam"]?.let { put("limitParam", it) }
            old["limitValue"]?.let { put("limitValue", it) }
            put("sendLimitParam", JsonPrimitive(sendLimit))
            old["extraParams"]?.let { put("extraParams", it) }
            old["incrementalOverlapDays"]?.let { put("incrementalOverlapDays", it) }
        },
    )
}

private fun pagingJson(
    type: String,
    vararg fields: Pair<String, Any?>,
): JsonObject =
    JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            fields.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> error("unsupported $value")
                }
            }
        },
    )

/**
 * API config v2 → v3: one direction vocabulary, shared with CSV strategies.
 * - Transaction mappings' `directionFromAmountSign` / `signSource` + `signField` + `creditValues` become
 *   `direction`: the amount's sign, or a field's value; neither keeps the endpoint's own (a bank feed's
 *   signed amount, a deposit endpoint's in, a withdrawal endpoint's out).
 * - Data endpoints drop `fixedDirection`, which only ever restated their kind.
 */
internal val apiDirectionStep =
    ConfigMigrationStep { config ->
        config.rewriteObjects { obj ->
            when {
                "amountField" in obj -> transactionMappingsOntoDirection(obj)
                "kind" in obj && "endpoint" in obj -> JsonObject(obj.without("fixedDirection"))
                else -> obj
            }
        } as JsonObject
    }

private fun transactionMappingsOntoDirection(obj: JsonObject): JsonObject {
    val signField = obj.string("signField")?.takeIf { obj.string("signSource") == "FIELD" }
    val direction =
        when {
            obj.string("directionFromAmountSign") == "true" -> JsonObject(mapOf("type" to JsonPrimitive("amountSign")))
            signField != null ->
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("field"),
                        "path" to JsonPrimitive(signField),
                        "incomingValues" to JsonArray(obj.stringList("creditValues").sorted().map(::JsonPrimitive)),
                    ),
                )
            else -> null
        }
    val rest = obj.without("directionFromAmountSign", "signSource", "signField", "creditValues")
    return JsonObject(if (direction == null) rest else rest + ("direction" to direction))
}

/**
 * API config v3 → v4: one fee rule and one ledger-grouping setting.
 * - A transaction mapping's `feeAmountField` family (currency, description, included-in-amount, and the
 *   instrument whose quote asset carries a repeated fee) becomes a `fee` rule; so does a trade mapping's
 *   `feeField` + `feeCurrencyField`.
 * - `reconcileTradeAmountsField` (+ fallbacks) and the `unpairedTradeLeg*` fields become `ledgerTrades`.
 */
internal val apiFeeAndLedgerStep =
    ConfigMigrationStep { config ->
        config.rewriteObjects { obj ->
            when {
                "amountField" in obj -> transactionFeeAndLedger(obj)
                "baseQuantityField" in obj -> tradeFee(obj)
                else -> obj
            }
        } as JsonObject
    }

private fun transactionFeeAndLedger(obj: JsonObject): JsonObject {
    val rest =
        obj.without(
            "feeAmountField",
            "feeCurrencyField",
            "feeDescriptionField",
            "feeIncludedInAmount",
            "feeInstrumentField",
            "feeInstrumentSeparator",
            "reconcileTradeAmountsField",
            "reconcileTradeAmountsFallbackFields",
            "unpairedTradeLegCounterAmountField",
            "unpairedTradeLegFundingAccountName",
        )
    val fee =
        obj.string("feeAmountField")?.let { amount ->
            buildMap {
                put("amount", valueExprJson(listOf(amount), null))
                obj.string("feeCurrencyField")?.let { put("currency", valueExprJson(listOf(it), null)) }
                obj.string("feeDescriptionField")?.let { put("description", valueExprJson(listOf(it), null)) }
                if (obj.string("feeIncludedInAmount") == "true") put("includedInAmount", JsonPrimitive(true))
                obj.string("feeInstrumentField")?.let { instrument ->
                    // The quote asset is what follows the last separator; a symbol without one is all quote.
                    val separator = escapeRegex(obj.string("feeInstrumentSeparator") ?: "-")
                    val afterLast =
                        JsonObject(
                            mapOf(
                                "pattern" to JsonPrimitive("^.*$separator(.*)$"),
                                "outputTemplate" to JsonPrimitive("$1"),
                            ),
                        )
                    put("chargedOnAsset", valueExprJson(listOf(instrument), afterLast))
                }
            }
        }
    val keyPaths = listOfNotNull(obj.string("reconcileTradeAmountsField")) + obj.stringList("reconcileTradeAmountsFallbackFields")
    val ledgerTrades =
        keyPaths.takeIf { it.isNotEmpty() }?.let { paths ->
            buildMap {
                put("key", valueExprJson(paths, null))
                obj.string("unpairedTradeLegCounterAmountField")?.let { put("unpairedCounterAmountPath", JsonPrimitive(it)) }
                obj.string("unpairedTradeLegFundingAccountName")?.let { put("unpairedFundingAccountName", JsonPrimitive(it)) }
            }
        }
    return JsonObject(
        rest + listOfNotNull(fee?.let { "fee" to JsonObject(it) }, ledgerTrades?.let { "ledgerTrades" to JsonObject(it) }),
    )
}

private fun tradeFee(obj: JsonObject): JsonObject {
    val rest = obj.without("feeField", "feeCurrencyField")
    val amount = obj.string("feeField") ?: return JsonObject(rest)
    val fee =
        buildMap {
            put("amount", valueExprJson(listOf(amount), null))
            obj.string("feeCurrencyField")?.let { put("currency", valueExprJson(listOf(it), null)) }
        }
    return JsonObject(rest + ("fee" to JsonObject(fee)))
}

private const val REGEX_SPECIALS = "\\^$.|?*+()[]{}"

private fun escapeRegex(literal: String): String = literal.map { if (it in REGEX_SPECIALS) "\\$it" else "$it" }.joinToString("")

/**
 * API config v4 → v5: a transaction mapping's `localAmountField` + `localCurrencyField` become one
 * `foreignAmount` (the shared `ForeignAmount`), which books a foreign purchase in its own currency.
 * Before, the pair was only ever read for custom attributes, so a mapping naming just one of them had
 * nothing to convert and loses the field.
 */
internal val apiForeignAmountStep =
    ConfigMigrationStep { config ->
        config.rewriteObjects { obj ->
            if ("amountField" in obj) transactionForeignAmount(obj) else obj
        } as JsonObject
    }

private fun transactionForeignAmount(obj: JsonObject): JsonObject {
    val rest = obj.without("localAmountField", "localCurrencyField")
    val amount = obj.string("localAmountField") ?: return JsonObject(rest)
    val currency = obj.string("localCurrencyField") ?: return JsonObject(rest)
    val foreign =
        JsonObject(
            mapOf(
                "amount" to valueExprJson(listOf(amount), null),
                "currency" to valueExprJson(listOf(currency), null),
            ),
        )
    return JsonObject(rest + ("foreignAmount" to foreign))
}
