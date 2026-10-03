package com.moneymanager.database.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * JSON helpers for migration steps that move a config onto the shared rule vocabulary
 * (`Condition`, `ValueExpr`, `AssetCodeRules` in app/model/rules).
 */

/** Applies [transform] to every object nested anywhere in this element, innermost first. */
internal fun JsonElement.rewriteObjects(transform: (JsonObject) -> JsonObject): JsonElement =
    when (this) {
        is JsonObject -> transform(JsonObject(mapValues { (_, value) -> value.rewriteObjects(transform) }))
        is JsonArray -> JsonArray(map { it.rewriteObjects(transform) })
        else -> this
    }

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

internal fun JsonObject.without(vararg keys: String): Map<String, JsonElement> = filterKeys { it !in keys }

internal fun jsonString(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

/** A `Condition` object. */
internal fun conditionJson(
    path: String,
    op: String,
    value: String? = null,
    otherPath: String? = null,
): JsonObject =
    JsonObject(
        mapOf(
            "path" to JsonPrimitive(path),
            "op" to JsonPrimitive(op),
            "value" to jsonString(value),
            "otherPath" to jsonString(otherPath),
        ),
    )

/** A `ValueExpr` object reading [paths] in order, optionally cleaned through [extraction]. */
internal fun valueExprJson(
    paths: List<String>,
    extraction: JsonElement?,
): JsonObject =
    JsonObject(
        buildMap {
            put("paths", JsonArray(paths.map(::JsonPrimitive)))
            if (extraction != null && extraction !is JsonNull) put("extraction", extraction)
        },
    )

/** An `AssetCodeRules` object, or null when both parts are empty (the default, which is omitted). */
internal fun assetCodesJson(
    aliases: JsonElement?,
    stripSuffixes: JsonElement?,
): JsonObject? {
    val hasAliases = (aliases as? JsonObject)?.isNotEmpty() == true
    val hasSuffixes = (stripSuffixes as? JsonArray)?.isNotEmpty() == true
    if (!hasAliases && !hasSuffixes) return null
    return JsonObject(
        buildMap {
            if (hasAliases) put("aliases", aliases)
            if (hasSuffixes) put("stripSuffixes", stripSuffixes)
        },
    )
}

/** Whether this object is a serialized instance of the class whose serial name ends with one of [simpleNames]. */
internal fun JsonObject.isType(vararg simpleNames: String): Boolean {
    val type = string("type") ?: return false
    return simpleNames.any { type.endsWith(".$it") }
}
