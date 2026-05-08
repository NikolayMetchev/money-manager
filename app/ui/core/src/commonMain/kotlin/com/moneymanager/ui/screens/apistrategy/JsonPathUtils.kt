package com.moneymanager.ui.screens.apistrategy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun extractJsonPaths(jsonText: String): List<JsonPathEntry> =
    try {
        val results = mutableListOf<JsonPathEntry>()
        walkElement(Json.parseToJsonElement(jsonText), "", results)
        results
    } catch (_: Exception) {
        emptyList()
    }

/**
 * Given a full API response body and the key whose value is the items array,
 * returns the first item in that array as a JSON string, or null if not found.
 */
fun extractFirstArrayItem(
    responseJson: String,
    arrayKey: String,
): String? =
    try {
        val root = Json.parseToJsonElement(responseJson)
        ((root as? JsonObject)?.get(arrayKey) as? JsonArray)?.firstOrNull()?.toString()
    } catch (_: Exception) {
        null
    }

private fun walkElement(
    element: JsonElement,
    path: String,
    results: MutableList<JsonPathEntry>,
) {
    when (element) {
        is JsonObject ->
            element.forEach { (key, value) ->
                walkElement(value, if (path.isEmpty()) key else "$path.$key", results)
            }
        // Use the first array element to discover item-level paths
        is JsonArray -> element.firstOrNull()?.let { walkElement(it, path, results) }
        is JsonPrimitive ->
            if (path.isNotEmpty()) {
                results.add(JsonPathEntry(path, element.content.take(60)))
            }
    }
}
