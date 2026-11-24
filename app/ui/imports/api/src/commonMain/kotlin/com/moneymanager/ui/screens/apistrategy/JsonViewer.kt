@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.ui.util.ContentCopyIcon
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun JsonViewer(
    json: String,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val jsonElement =
        remember(json) {
            runCatching { Json.parseToJsonElement(json) }.getOrNull()
        }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prettyPrinter = remember { Json { prettyPrint = true } }
    // Parse the highlight path into segments (e.g. "$.transactions[0]" → ["transactions", "0"])
    val highlightSegments =
        remember(highlightJsonPath) {
            parseJsonPathSegments(highlightJsonPath)
        }
    val latestTransactionByJsonPath =
        remember(responseTransactions) {
            responseTransactions
                .groupBy { it.jsonPath.value }
                .mapValues { (_, transactions) ->
                    transactions.maxBy { it.id.id }
                }
        }

    if (jsonElement == null) {
        SelectionContainer {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column {
            JsonTreeNode(
                label = null,
                element = jsonElement,
                jsonPath = "$",
                depth = 0,
                latestTransactionByJsonPath = latestTransactionByJsonPath,
                remainingHighlightSegments = highlightSegments,
                onHighlightPositioned = onHighlightPositioned,
                clipboard = clipboard,
                copyScope = scope,
                prettyPrinter = prettyPrinter,
            )
        }
    }
}

/**
 * Returns true if this path equals [prefix] or is a child of it
 * (e.g. "$.transactions[0].counterparty".startsWithJsonPath("$.transactions[0]") == true).
 */
internal fun String.startsWithJsonPath(prefix: String): Boolean =
    this == prefix || this.startsWith("$prefix.") || this.startsWith("$prefix[")

/**
 * Converts a simple JSONPath (e.g. "$.transactions[2]") into a list of
 * string segments (["transactions", "2"]) used to trace the highlight path
 * through the JSON tree.  Returns null when no highlight is needed.
 */
internal fun parseJsonPathSegments(jsonPath: String?): List<String>? {
    if (jsonPath == null) return null
    // Strip leading "$." prefix, then split on "." and "[…]"
    val stripped = jsonPath.removePrefix("$.").removePrefix("$")
    if (stripped.isEmpty()) return null

    val segments = mutableListOf<String>()
    // Split on "." first, then strip "[index]" from each part
    for (part in stripped.split(".")) {
        val bracketStart = part.indexOf('[')
        if (bracketStart < 0) {
            segments.add(part)
        } else {
            if (bracketStart > 0) segments.add(part.substring(0, bracketStart))
            val bracketEnd = part.indexOf(']', bracketStart)
            if (bracketEnd > bracketStart) {
                segments.add(part.substring(bracketStart + 1, bracketEnd))
            }
        }
    }
    return segments.ifEmpty { null }
}

internal fun isHighlightTarget(remainingHighlightSegments: List<String>?): Boolean =
    remainingHighlightSegments != null && remainingHighlightSegments.isEmpty()

internal fun shouldHighlightPair(
    pair: ApiTrafficPair,
    responseTransactions: List<ApiResponseTransaction>,
    highlightRequestId: ApiRequestId?,
    highlightJsonPath: String?,
): Boolean {
    val requestMatches =
        highlightRequestId == null ||
            pair.request?.id == highlightRequestId ||
            pair.response?.requestId == highlightRequestId
    val jsonPathMatches =
        highlightJsonPath != null &&
            responseTransactions.any {
                highlightJsonPath.startsWithJsonPath(it.jsonPath.value)
            }
    // A known request id pins the pair on its own, so highlight it (and let the JSON tree expand the
    // path) even when the response has no parsed transactions to match against — e.g. the accounts
    // response, whose own-account origin is "$.accounts[0]". This still requires a response to expand
    // into; with no response and an unmatched path there is nothing to show. Transaction matching is
    // only needed to disambiguate which page holds a source when no request id is supplied.
    return (highlightRequestId != null || highlightJsonPath != null) &&
        requestMatches &&
        (
            jsonPathMatches ||
                (highlightRequestId != null && (highlightJsonPath == null || pair.response != null))
        )
}

@Composable
private fun JsonTreeNode(
    label: String?,
    element: JsonElement,
    jsonPath: String,
    depth: Int,
    clipboard: Clipboard,
    copyScope: CoroutineScope,
    prettyPrinter: Json,
    latestTransactionByJsonPath: Map<String, ApiResponseTransaction> = emptyMap(),
    remainingHighlightSegments: List<String>? = null,
    forceExpandSubtree: Boolean = false,
    highlightSubtree: Boolean = false,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val childCount = element.childCount()
    val expandable = childCount > 0
    // This node is the target only when a highlight path exists and all segments are consumed.
    val isHighlightTarget = isHighlightTarget(remainingHighlightSegments)
    // Force-expand the node if it's on the highlight path
    val forceExpandPath = !remainingHighlightSegments.isNullOrEmpty()
    val shouldForceExpand = forceExpandSubtree || forceExpandPath || isHighlightTarget
    var expanded by remember(label, element, remainingHighlightSegments, forceExpandSubtree) {
        mutableStateOf(depth == 0 || shouldForceExpand)
    }

    val prefix =
        if (expandable) {
            if (expanded) "- " else "+ "
        } else {
            "  "
        }
    val line =
        buildString {
            append(prefix)
            if (label != null) {
                append(label)
                append(": ")
            }
            append(element.summary(expanded))
        }

    val nodeTransaction = latestTransactionByJsonPath[jsonPath]
    val highlightBackground =
        when {
            isHighlightTarget -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
            highlightSubtree -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            nodeTransaction != null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    val highlightTextColor =
        if (isHighlightTarget) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val lineModifier =
        Modifier
            .fillMaxWidth()
            .background(highlightBackground, MaterialTheme.shapes.extraSmall)
            .padding(start = (depth * 12).dp)
            .then(
                if (isHighlightTarget && onHighlightPositioned != null) {
                    Modifier.testTag("apiTrafficHighlight").onGloballyPositioned { coords ->
                        onHighlightPositioned(coords.positionInRoot().y)
                    }
                } else {
                    Modifier
                },
            ).then(
                if (expandable) {
                    Modifier.clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            )

    Row(
        modifier = lineModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = highlightTextColor,
            modifier = Modifier.weight(1f),
        )
        nodeTransaction?.let { transaction ->
            JsonTransactionStatus(transaction = transaction)
        }
        Icon(
            imageVector = ContentCopyIcon,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .size(14.dp)
                    .clickable { copyScope.launch { clipboard.setPlainText(prettyPrinter.encodeToString<JsonElement>(element)) } },
        )
    }
    if (expanded) {
        when (element) {
            is JsonObject -> {
                element.entries.forEach { (key, value) ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments.isNullOrEmpty() -> null
                            remainingHighlightSegments.first() == key -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "\"$key\"",
                        element = value,
                        jsonPath = jsonObjectChildPath(jsonPath, key),
                        depth = depth + 1,
                        clipboard = clipboard,
                        copyScope = copyScope,
                        prettyPrinter = prettyPrinter,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = highlightSubtree || isHighlightTarget,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }

            is JsonArray -> {
                element.forEachIndexed { index, value ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments.isNullOrEmpty() -> null
                            remainingHighlightSegments.first() == index.toString() -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "[$index]",
                        element = value,
                        jsonPath = "$jsonPath[$index]",
                        depth = depth + 1,
                        clipboard = clipboard,
                        copyScope = copyScope,
                        prettyPrinter = prettyPrinter,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = highlightSubtree || isHighlightTarget,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }

            else -> {}
        }
    }
}

@Composable
private fun JsonTransactionStatus(transaction: ApiResponseTransaction) {
    val stateColor =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> MaterialTheme.colorScheme.primary
            ApiResponseTransactionState.DUPLICATE -> MaterialTheme.colorScheme.tertiary
            ApiResponseTransactionState.ERROR -> MaterialTheme.colorScheme.error
        }
    val label =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> "Imported"
            ApiResponseTransactionState.DUPLICATE -> "Duplicate"
            ApiResponseTransactionState.ERROR -> "Error"
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = stateColor,
        maxLines = 1,
        modifier =
            Modifier
                .background(stateColor.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun jsonObjectChildPath(
    parentPath: String,
    key: String,
): String =
    if (parentPath == "$") {
        "$.$key"
    } else {
        "$parentPath.$key"
    }

private fun JsonElement.childCount(): Int =
    when (this) {
        is JsonObject -> size
        is JsonArray -> size
        else -> 0
    }

private fun JsonElement.summary(expanded: Boolean): String =
    when (this) {
        is JsonObject -> if (expanded) "{" else "{...} $size item(s)"
        is JsonArray -> if (expanded) "[" else "[...] $size item(s)"
        JsonNull -> "null"
        is JsonPrimitive -> toString()
    }
