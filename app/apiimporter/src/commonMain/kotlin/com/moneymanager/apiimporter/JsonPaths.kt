package com.moneymanager.apiimporter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Resolves a dot-notation path (e.g. "merchant.name") against this JSON object. */
internal fun JsonObject.resolveJsonPath(dotPath: String): String? = (resolveJsonPathElement(dotPath) as? JsonPrimitive)?.contentOrNull

/**
 * Resolves a dot-notation path to a `JsonElement`, supporting array indexing so exchange responses
 * whose items live inside nested arrays are addressable — e.g. "result.data", "data[0].price",
 * "legs[1].amount". Each segment is an optional object key followed by zero or more `[n]` indices.
 */
internal fun JsonElement.resolveJsonPathElement(dotPath: String): JsonElement? {
    if (dotPath.isEmpty()) return this
    var current: JsonElement = this
    for (rawPart in dotPath.split(".")) {
        val bracketStart = rawPart.indexOf('[')
        val key = if (bracketStart >= 0) rawPart.substring(0, bracketStart) else rawPart
        if (key.isNotEmpty()) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        if (bracketStart >= 0) {
            var rest = rawPart.substring(bracketStart)
            while (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                if (end < 0) return null
                val index = rest.substring(1, end).toIntOrNull() ?: return null
                current = (current as? JsonArray)?.getOrNull(index) ?: return null
                rest = rest.substring(end + 1)
            }
            // Reject a malformed segment with trailing text after the brackets (e.g. "data[0]foo").
            if (rest.isNotEmpty()) return null
        }
    }
    return current
}
