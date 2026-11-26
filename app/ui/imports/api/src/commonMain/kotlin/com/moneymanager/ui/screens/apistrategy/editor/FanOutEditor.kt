package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiFanOut
import com.moneymanager.domain.model.apistrategy.ApiValueSet

@Composable
internal fun FanOutEditor(
    fanOut: ApiFanOut?,
    onChange: (ApiFanOut?) -> Unit,
    enabled: Boolean,
) {
    ToggleRow("Download once per value", fanOut != null, {
        onChange(if (it) ApiFanOut(null, ApiValueSet.Static(emptyList())) else null)
    }, enabled)
    if (fanOut == null) return
    TextFieldRow(
        "Fan-out parameter",
        fanOut.param.orEmpty(),
        { onChange(fanOut.copy(param = it.ifBlank { null })) },
        enabled,
        supportingText = "Leave blank to substitute the value only in a {fanOut} endpoint path.",
    )
    ToggleRow("Preserve value case", fanOut.preserveCase, { onChange(fanOut.copy(preserveCase = it)) }, enabled)
    ValueSetEditor("Download values", fanOut.values, { onChange(fanOut.copy(values = it)) }, enabled)
    ToggleRow("Filter against a validation set", fanOut.validAgainst != null, {
        onChange(fanOut.copy(validAgainst = if (it) ApiValueSet.FromValueEndpoint("", emptyList()) else null))
    }, enabled)
    fanOut.validAgainst?.let { valid ->
        ValueSetEditor("Validation values", valid, { onChange(fanOut.copy(validAgainst = it)) }, enabled)
    }
}

private enum class Source(
    val label: String,
    val initial: ApiValueSet,
) {
    STATIC("Fixed values", ApiValueSet.Static(emptyList())),
    VALUE("Value endpoint", ApiValueSet.FromValueEndpoint("", emptyList())),
    DATA("Data endpoint", ApiValueSet.FromDataEndpoint("", emptyList())),
    UNION("Union", ApiValueSet.Union(emptyList())),
    PRODUCT("Cross product", ApiValueSet.CrossProduct(ApiValueSet.Static(emptyList()), ApiValueSet.Static(emptyList()))),
}

@Composable
private fun ValueSetEditor(
    label: String,
    set: ApiValueSet,
    onChange: (ApiValueSet) -> Unit,
    enabled: Boolean,
) {
    val source =
        when (set) {
            is ApiValueSet.Static -> Source.STATIC
            is ApiValueSet.FromValueEndpoint -> Source.VALUE
            is ApiValueSet.FromDataEndpoint -> Source.DATA
            is ApiValueSet.Union -> Source.UNION
            is ApiValueSet.CrossProduct -> Source.PRODUCT
        }
    EnumDropdown(label, Source.entries, source, { if (it != source) onChange(it.initial) }, { it.label }, enabled)
    Column(Modifier.padding(start = 12.dp)) {
        when (set) {
            is ApiValueSet.Static -> {
                StringListEditor("Fixed values", set.values, { onChange(set.copy(values = it)) }, enabled, "value")
            }

            is ApiValueSet.FromValueEndpoint -> {
                EndpointSourceEditor(set.endpointPath, set.fields, { path, fields ->
                    onChange(ApiValueSet.FromValueEndpoint(path, fields))
                }, enabled)
            }

            is ApiValueSet.FromDataEndpoint -> {
                EndpointSourceEditor(set.endpointPath, set.fields, { path, fields ->
                    onChange(ApiValueSet.FromDataEndpoint(path, fields))
                }, enabled)
            }

            is ApiValueSet.Union -> {
                set.sets.forEachIndexed { index, child ->
                    EditorCardHeader("Set ${index + 1}", { onChange(set.copy(sets = set.sets.minusAt(index))) }, enabled)
                    ValueSetEditor("Value source", child, { onChange(set.copy(sets = set.sets.replacingAt(index, it))) }, enabled)
                }
                TextButton(onClick = { onChange(set.copy(sets = set.sets + Source.STATIC.initial)) }, enabled = enabled) { Text("Add set") }
            }

            is ApiValueSet.CrossProduct -> {
                ValueSetEditor("Left set", set.left, { onChange(set.copy(left = it)) }, enabled)
                ValueSetEditor("Right set", set.right, { onChange(set.copy(right = it)) }, enabled)
                TextFieldRow(
                    "Combination template",
                    set.template,
                    { onChange(set.copy(template = it)) },
                    enabled,
                    supportingText = "Use {left} and {right} to insert values from each set.",
                )
            }
        }
    }
}

@Composable
private fun EndpointSourceEditor(
    path: String,
    fields: List<String>,
    onChange: (String, List<String>) -> Unit,
    enabled: Boolean,
) {
    TextFieldRow(
        "Source endpoint path",
        path,
        { onChange(it, fields) },
        enabled,
        supportingText = "For endpoints sharing a path, include their static query parameters sorted by name.",
    )
    StringListEditor("Response field paths", fields, { onChange(path, it) }, enabled, "field")
}

internal fun ApiFanOut.isValidForSave(): Boolean = values.isComplete() && validAgainst?.isComplete() != false

private fun ApiValueSet.isComplete(): Boolean =
    when (this) {
        is ApiValueSet.Static -> values.isPopulated()
        is ApiValueSet.FromValueEndpoint -> endpointPath.isNotBlank() && fields.isPopulated()
        is ApiValueSet.FromDataEndpoint -> endpointPath.isNotBlank() && fields.isPopulated()
        is ApiValueSet.Union -> sets.isNotEmpty() && sets.all { it.isComplete() }
        is ApiValueSet.CrossProduct -> template.isNotBlank() && left.isComplete() && right.isComplete()
    }

private fun List<String>.isPopulated(): Boolean = isNotEmpty() && none { it.isBlank() }
