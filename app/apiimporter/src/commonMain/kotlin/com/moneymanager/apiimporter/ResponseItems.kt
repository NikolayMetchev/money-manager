package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.JsonPath
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val logger = Logger.withTag("ApiResponseItems")

/** JSON path for the [index]-th item of a response array, accounting for a blank (root-array) key. */
internal fun arrayItemJsonPath(
    responseArrayKey: String,
    index: Int,
): JsonPath = if (responseArrayKey.isBlank()) JsonPath("$[$index]") else JsonPath("$.$responseArrayKey[$index]")

/**
 * The real JSON path of an item inside a keyed-object response (see [responseItemsWithKeys]). A [key] that
 * already starts with an index (`[0].details[1]`, produced by nested-array flattening) is appended without
 * the joining dot, so the path stays resolvable.
 */
internal fun keyedItemJsonPath(
    responseArrayKey: String,
    key: String,
): JsonPath {
    val separator = if (key.startsWith("[")) "" else "."
    return if (responseArrayKey.isBlank()) JsonPath("$$separator$key") else JsonPath("$.$responseArrayKey$separator$key")
}

/**
 * Extracts the items array from a response body; a blank [responseArrayKey] means the body is the
 * array. A bare JSON object body (e.g. a single-resource endpoint like Starling's account holder) is
 * wrapped as a one-element array so single-object responses parse like any other.
 *
 * When [responseObjectValues] is set, the element at [responseArrayKey] is itself a JSON object keyed
 * by id (Kraken `result.trades`/`result.ledger`) rather than an array; its values become the items. When
 * [itemKeyField] is also set, each entry's map key is spliced into that entry's object under this field
 * name first, since the id sometimes appears only as the key (Kraken ledger entries).
 *
 * When [nestedItemsKey] is set, each element at [responseArrayKey] is only a container: the items are the
 * elements of its nested array at that path (Binance `asset/dribblet`), each paired with its real
 * `[outer].<key>[inner]` path so the audit trail points at the row that was actually mapped.
 */
internal fun responseItemsArray(
    json: String,
    responseArrayKey: String,
    responseObjectValues: Boolean = false,
    itemKeyField: String? = null,
    nestedItemsKey: String? = null,
): JsonArray? =
    responseItemsWithKeys(json, responseArrayKey, responseObjectValues, itemKeyField, nestedItemsKey)
        ?.let { items -> JsonArray(items.map { (_, item) -> item }) }

/**
 * Like [responseItemsArray], but pairs each item with the real JSON-path key it lives at: the
 * object's own key for a keyed-object response ([responseObjectValues] — e.g. Kraken's
 * `result.trades`/`result.ledger`), or null for a plain array (callers fall back to an index-based
 * path via [arrayItemJsonPath]). Without this, an audit entry for a keyed-object item stored a
 * fabricated array-index path (e.g. `result.trades[3]`) that doesn't exist in the actual response, so
 * the "view origin" link couldn't locate it.
 */
internal fun responseItemsWithKeys(
    json: String,
    responseArrayKey: String,
    responseObjectValues: Boolean = false,
    itemKeyField: String? = null,
    nestedItemsKey: String? = null,
): List<Pair<String?, JsonElement>>? =
    try {
        val root = Json.parseToJsonElement(json)
        val resolved = if (responseArrayKey.isBlank()) root else root.resolveJsonPathElement(responseArrayKey)
        if (nestedItemsKey != null) {
            (resolved as? JsonArray)?.flatMapIndexed { outerIndex, container ->
                val nested = (container as? JsonObject)?.resolveJsonPathElement(nestedItemsKey) as? JsonArray
                nested.orEmpty().mapIndexed { innerIndex, item ->
                    "[$outerIndex].$nestedItemsKey[$innerIndex]" to item
                }
            }
        } else if (responseObjectValues) {
            (resolved as? JsonObject)?.entries?.map { (key, value) ->
                val item =
                    if (itemKeyField != null && value is JsonObject) {
                        JsonObject(value.toMutableMap().apply { put(itemKeyField, JsonPrimitive(key)) })
                    } else {
                        value
                    }
                key to item
            }
        } else if (responseArrayKey.isBlank()) {
            when (root) {
                is JsonArray -> root.map { null to it }
                is JsonObject -> listOf(null to root)
                else -> null
            }
        } else {
            (resolved as? JsonArray)?.map { null to it }
        }
    } catch (e: SerializationException) {
        logger.e(e) { "Failed to parse API response array (key='$responseArrayKey')" }
        null
    }

/**
 * Whether a response passed its envelope status check. Strategies with a [successCodeField]
 * (Crypto.com "code") require the value at that path to equal [successCodeOkValue] (e.g. "0").
 * Strategies with an [errorArrayField] (Kraken "error") require the array at that path to be absent or
 * empty. When both are null, no envelope check is applied (bank APIs rely on the HTTP status alone).
 */
internal fun responseCodeOk(
    json: String,
    successCodeField: String?,
    successCodeOkValue: String?,
    errorArrayField: String? = null,
): Boolean {
    val root =
        try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return false
        }
    if (errorArrayField != null) {
        val errors = root.resolveJsonPathElement(errorArrayField) as? JsonArray
        if (!errors.isNullOrEmpty()) return false
    }
    if (successCodeField == null) return true
    // Fail closed: a configured status field with no expected value must never pass (an absent code
    // would otherwise equal a null expected value and let an error envelope through).
    val expected = successCodeOkValue ?: return false
    val actual = (root.resolveJsonPathElement(successCodeField) as? JsonPrimitive)?.contentOrNull
    return actual == expected
}

internal const val MILLIS_PER_DAY = 86_400_000L

// '}' is matched via a character class because Android's regex engine rejects a bare '}'
// while the JVM flags an escaped '\}' as redundant.
internal val PATH_TEMPLATE_REGEX = Regex("\\{([^}]+)[}]")
