@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

/** Requests the JSON-path picker dialog over [paths], routing the chosen path to [setter]. */
internal typealias PathPicker = (paths: List<JsonPathEntry>, setter: (String) -> Unit) -> Unit

/** Holds the editable state for a single custom field mapping row. */
data class CustomFieldState(
    val name: String,
    val path: String,
    val isUniqueId: Boolean = false,
)

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Small status line indicating whether session data is available for path picking. */
@Composable
internal fun SessionDataStatus(
    paths: List<JsonPathEntry>,
    loaded: Boolean,
) {
    val text =
        when {
            !loaded -> "Loading session data…"
            paths.isNotEmpty() -> "${paths.size} paths available from last session — tap ⊞ to pick"
            else -> "No session data yet — type paths manually"
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** A plain labelled single-line text field. */
@Composable
internal fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
    )
}

/**
 * A field that edits an [Int]. Keeps its own text buffer so intermediate empty/invalid input is
 * shown without corrupting the model; commits only parseable values via [onValueChange].
 */
@Composable
internal fun IntFieldRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.toIntOrNull() == null,
        supportingText =
            if (text.toIntOrNull() == null) {
                { Text("Must be a whole number") }
            } else {
                null
            },
    )
}

/**
 * A field that edits a [Long] without narrowing. Keeps its own text buffer (keyed on [value] so an
 * external change resyncs it) so intermediate empty/invalid input is shown without corrupting the model.
 */
@Composable
internal fun LongFieldRow(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parseError = text.trim().toLongOrNull() == null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toLongOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = isError || parseError,
        supportingText =
            when {
                parseError -> {
                    { Text("Must be a whole number") }
                }
                supportingText != null -> {
                    { Text(supportingText) }
                }
                else -> null
            },
    )
}

/**
 * A field that edits an optional [Long]. A blank buffer commits `null`; keeps its own text buffer
 * (keyed on [value] so an external change resyncs it) so intermediate empty/invalid input is shown
 * without corrupting the model.
 */
@Composable
internal fun OptionalLongFieldRow(
    label: String,
    value: Long?,
    onValueChange: (Long?) -> Unit,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parseError = text.isNotBlank() && text.trim().toLongOrNull() == null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) {
                onValueChange(null)
            } else {
                it.trim().toLongOrNull()?.let(onValueChange)
            }
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = parseError,
        supportingText =
            if (parseError) {
                { Text("Must be a whole number, or blank for the default") }
            } else {
                null
            },
    )
}

/** A Switch + label row. */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(text = label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A generic dropdown over [options], rendering each via [optionLabel]. */
@Composable
internal fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * A single field-mapping row backed by a dot-path. When session [paths] are available the ⊞ icon
 * opens the JSON-path picker via [onRequestPick].
 */
@Composable
internal fun PathFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    paths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
        )
        if (paths.isNotEmpty()) {
            IconButton(onClick = { onRequestPick(paths, onValueChange) }, enabled = enabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = "Pick from session JSON",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Card header: a title and a remove (✕) button, for items in a list editor. */
@Composable
internal fun EditorCardHeader(
    title: String,
    onRemove: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Edits a set of plain string values (e.g. credit values, country codes). */
@Composable
internal fun StringSetEditor(
    label: String,
    values: Set<String>,
    onChange: (Set<String>) -> Unit,
    enabled: Boolean = true,
) {
    val items = remember(values) { values.toList() }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = item,
                onValueChange = { updated ->
                    onChange(
                        items
                            .toMutableList()
                            .also { it[index] = updated }
                            .filter { it.isNotBlank() }
                            .toSet(),
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = enabled,
            )
            IconButton(onClick = { onChange(items.toMutableList().also { it.removeAt(index) }.toSet()) }, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = "Remove value", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    TextButton(onClick = { onChange(values + "") }, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add value")
    }
}

/**
 * Edits an ordered `List<String>` as numbered rows (e.g. connect instructions), with add/remove/reorder
 * by index. Unlike [StringMapEditor] there is no key/identity conflict to guard against — a blank or
 * duplicate row is simply another list entry — so [onChange] can be called directly on every edit.
 */
@Composable
internal fun StringListEditor(
    label: String,
    items: List<String>,
    onChange: (List<String>) -> Unit,
    enabled: Boolean = true,
) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = item,
                onValueChange = { updated -> onChange(items.toMutableList().also { it[index] = updated }) },
                label = { Text("Step ${index + 1}") },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            IconButton(onClick = { onChange(items.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = "Remove step", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    TextButton(onClick = { onChange(items + "") }, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add step")
    }
}

/**
 * Edits a `Map<String, String>` as rows of key/value text fields (e.g. asset aliases, account aliases).
 * Row identity is a local, independent editing buffer (seeded once from [entries], not recomputed from
 * it) so an in-progress blank or duplicate key never collapses or drops another row mid-edit — only the
 * well-formed rows (non-blank key) are converted to the map handed to [onChange], with the usual
 * last-key-wins behavior on duplicates.
 */
@Composable
internal fun StringMapEditor(
    label: String,
    entries: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    keyLabel: String = "Key",
    valueLabel: String = "Value",
    enabled: Boolean = true,
) {
    val rows = remember { entries.toList().toMutableStateList() }

    fun emit() {
        onChange(rows.filter { it.first.isNotBlank() }.associate { it.first to it.second })
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rows.forEachIndexed { index, (key, value) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = key,
                onValueChange = { updated ->
                    rows[index] = updated to value
                    emit()
                },
                label = { Text(keyLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = enabled,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { updated ->
                    rows[index] = key to updated
                    emit()
                },
                label = { Text(valueLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = enabled,
            )
            IconButton(
                onClick = {
                    rows.removeAt(index)
                    emit()
                },
                enabled = enabled,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove entry", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    TextButton(onClick = { rows.add("" to "") }, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add entry")
    }
}

/** Expandable list of user-defined custom field mappings with add and remove controls. */
@Composable
internal fun CustomFieldsSection(
    fields: List<CustomFieldState>,
    onFieldsChange: (List<CustomFieldState>) -> Unit,
    paths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean = true,
) {
    Text(
        text = "Custom Fields",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    fields.forEachIndexed { index, field ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = field.name,
                    onValueChange = { updated ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(name = updated) })
                    },
                    label = { Text("Field name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                )
                OutlinedTextField(
                    value = field.path,
                    onValueChange = { updated ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(path = updated) })
                    },
                    label = { Text("JSON path") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                )
                if (paths.isNotEmpty()) {
                    IconButton(onClick = {
                        onRequestPick(paths) { selected ->
                            onFieldsChange(fields.toMutableList().also { it[index] = field.copy(path = selected) })
                        }
                    }, enabled = enabled) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Pick from session JSON",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = {
                    onFieldsChange(fields.toMutableList().also { it.removeAt(index) })
                }, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Remove field", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                Checkbox(
                    checked = field.isUniqueId,
                    onCheckedChange = { checked ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(isUniqueId = checked) })
                    },
                    enabled = enabled,
                )
                Text(
                    text = "Use as unique identifier for duplicate detection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    TextButton(
        onClick = { onFieldsChange(fields + CustomFieldState("", "")) },
        modifier = Modifier.padding(top = 2.dp),
        enabled = enabled,
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add Custom Field")
    }
}

/** Edits a list of [ApiQueryParam] (name / static value / dynamic source). */
@Composable
internal fun QueryParamsEditor(
    params: List<ApiQueryParam>,
    onChange: (List<ApiQueryParam>) -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = "Query Parameters",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    params.forEachIndexed { index, param ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditorCardHeader(
                    title = param.name.ifBlank { "Parameter ${index + 1}" },
                    onRemove = { onChange(params.toMutableList().also { it.removeAt(index) }) },
                    enabled = enabled,
                )
                TextFieldRow(
                    label = "Name",
                    value = param.name,
                    onValueChange = { updated ->
                        onChange(params.toMutableList().also { it[index] = param.copy(name = updated) })
                    },
                    enabled = enabled,
                )
                TextFieldRow(
                    label = "Static value (optional)",
                    value = param.value.orEmpty(),
                    onValueChange = { updated ->
                        onChange(params.toMutableList().also { it[index] = param.copy(value = updated.ifBlank { null }) })
                    },
                    enabled = enabled,
                )
                TextFieldRow(
                    label = "Dynamic source (optional, e.g. account.id)",
                    value = param.dynamicSource.orEmpty(),
                    onValueChange = { updated ->
                        onChange(params.toMutableList().also { it[index] = param.copy(dynamicSource = updated.ifBlank { null }) })
                    },
                    enabled = enabled,
                )
            }
        }
    }
    TextButton(onClick = { onChange(params + ApiQueryParam(name = "")) }, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add Parameter")
    }
}

/** Edits an optional [ApiPaginationConfig]; a toggle enables/clears it. */
@Composable
internal fun PaginationEditor(
    pagination: ApiPaginationConfig?,
    onChange: (ApiPaginationConfig?) -> Unit,
    enabled: Boolean = true,
) {
    ToggleRow(
        label = "Enable pagination",
        checked = pagination != null,
        onCheckedChange = { onChange(if (it) ApiPaginationConfig() else null) },
        enabled = enabled,
    )
    val config = pagination ?: return
    EnumDropdown(
        label = "Mode",
        options = PaginationMode.entries,
        selected = config.mode,
        onSelect = { onChange(config.copy(mode = it)) },
        optionLabel = { it.name },
        enabled = enabled,
    )
    TextFieldRow("Limit param", config.limitParam, { onChange(config.copy(limitParam = it)) }, enabled)
    IntFieldRow("Limit value", config.limitValue, { onChange(config.copy(limitValue = it)) }, enabled)
    when (config.mode) {
        PaginationMode.CURSOR -> {
            TextFieldRow("Cursor param", config.cursorParam, { onChange(config.copy(cursorParam = it)) }, enabled)
            TextFieldRow("Cursor response field", config.cursorResponseField, { onChange(config.copy(cursorResponseField = it)) }, enabled)
        }
        PaginationMode.DATE_WINDOW -> {
            TextFieldRow("Start param", config.startParam, { onChange(config.copy(startParam = it)) }, enabled)
            TextFieldRow("End param", config.endParam, { onChange(config.copy(endParam = it)) }, enabled)
            EnumDropdown(
                label = "Window bound format",
                options = WindowBoundFormat.entries,
                selected = config.windowBoundFormat,
                onSelect = { onChange(config.copy(windowBoundFormat = it)) },
                optionLabel = { it.name },
                enabled = enabled,
            )
            IntFieldRow("Window days", config.windowDays, { onChange(config.copy(windowDays = it)) }, enabled)
            IntFieldRow("Lookback days", config.lookbackDays, { onChange(config.copy(lookbackDays = it)) }, enabled)
            QueryParamsEditor(params = config.extraParams, onChange = { onChange(config.copy(extraParams = it)) }, enabled = enabled)
        }
    }
    ToggleRow(
        label = "Offset paging (e.g. Kraken \"ofs\")",
        checked = config.offsetParam != null,
        onCheckedChange = { onChange(config.copy(offsetParam = if (it) "ofs" else null)) },
        enabled = enabled,
    )
    config.offsetParam?.let { offsetParam ->
        TextFieldRow("Offset param", offsetParam, { onChange(config.copy(offsetParam = it)) }, enabled)
        TextFieldRow(
            "Total count field (optional)",
            config.totalCountField.orEmpty(),
            { onChange(config.copy(totalCountField = it.ifBlank { null })) },
            enabled,
        )
    }
}

/** Edits a single [ApiEndpointConfig]: path, response array key, query params, pagination. */
@Composable
internal fun EndpointEditor(
    endpoint: ApiEndpointConfig,
    onChange: (ApiEndpointConfig) -> Unit,
    enabled: Boolean = true,
    pathRequired: Boolean = true,
) {
    TextFieldRow(
        label = "Path",
        value = endpoint.path,
        onValueChange = { onChange(endpoint.copy(path = it)) },
        enabled = enabled,
        placeholder = "/accounts",
        isError = pathRequired && endpoint.path.isBlank(),
    )
    TextFieldRow(
        label = "Response array key",
        value = endpoint.responseArrayKey,
        onValueChange = { onChange(endpoint.copy(responseArrayKey = it)) },
        enabled = enabled,
        placeholder = "accounts (blank = response is a bare array)",
    )
    EnumDropdown(
        label = "HTTP method",
        options = HttpMethodType.entries,
        selected = endpoint.method,
        onSelect = { onChange(endpoint.copy(method = it)) },
        optionLabel = { it.name },
        enabled = enabled,
    )
    ToggleRow(
        label = "Response array key is a keyed object (e.g. Kraken result.trades)",
        checked = endpoint.responseObjectValues,
        onCheckedChange = { onChange(endpoint.copy(responseObjectValues = it)) },
        enabled = enabled,
    )
    if (endpoint.responseObjectValues) {
        TextFieldRow(
            label = "Splice object key into field (optional, e.g. ledger_id)",
            value = endpoint.itemKeyField.orEmpty(),
            onValueChange = { onChange(endpoint.copy(itemKeyField = it.ifBlank { null })) },
            enabled = enabled,
        )
    }
    TextFieldRow(
        label = "Success code field (optional, e.g. code)",
        value = endpoint.successCodeField.orEmpty(),
        onValueChange = { onChange(endpoint.copy(successCodeField = it.ifBlank { null })) },
        enabled = enabled,
    )
    if (endpoint.successCodeField != null) {
        TextFieldRow(
            label = "Success code value (e.g. 0)",
            value = endpoint.successCodeOkValue.orEmpty(),
            onValueChange = { onChange(endpoint.copy(successCodeOkValue = it.ifBlank { null })) },
            enabled = enabled,
            isError = endpoint.successCodeOkValue.isNullOrBlank(),
        )
    }
    TextFieldRow(
        label = "Error array field (optional, e.g. error)",
        value = endpoint.errorArrayField.orEmpty(),
        onValueChange = { onChange(endpoint.copy(errorArrayField = it.ifBlank { null })) },
        enabled = enabled,
    )
    IntFieldRow(
        label = "Relative rate-limit cost (e.g. Kraken's history calls cost 2 vs 1 for others)",
        value = endpoint.requestCostWeight,
        onValueChange = { onChange(endpoint.copy(requestCostWeight = it)) },
        enabled = enabled,
    )
    QueryParamsEditor(
        params = endpoint.queryParams,
        onChange = { onChange(endpoint.copy(queryParams = it)) },
        enabled = enabled,
    )
    PaginationEditor(
        pagination = endpoint.pagination,
        onChange = { onChange(endpoint.copy(pagination = it)) },
        enabled = enabled,
    )
}

/**
 * Dialog showing JSON paths extracted from a real session response. Tapping a row selects that
 * path for the field being edited.
 */
@Composable
internal fun JsonNodePickerDialog(
    paths: List<JsonPathEntry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select JSON field") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(paths) { entry ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.path) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(text = entry.path, style = MaterialTheme.typography.bodyMedium)
                        if (entry.preview.isNotEmpty()) {
                            Text(
                                text = entry.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
