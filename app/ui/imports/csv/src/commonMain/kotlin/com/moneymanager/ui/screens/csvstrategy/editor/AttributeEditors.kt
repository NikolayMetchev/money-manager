package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.ui.components.rules.ExtractionEditor
import com.moneymanager.ui.components.transactions.AttributeTypeField

/**
 * Expandable multi-select checkbox list for identification columns.
 * Shows a compact summary by default with option to expand for column selection.
 */
@Composable
internal fun IdentificationColumnsSelector(
    columns: List<CsvColumn>,
    selectedColumns: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val allSelected = selectedColumns.size == columns.size

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    if (allSelected) {
                        "All Columns (${columns.size})"
                    } else {
                        "${selectedColumns.size} of ${columns.size} columns"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (selectedColumns.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onSelectionChanged(columns.map { it.originalName }.toSet())
                            } else {
                                onSelectionChanged(emptySet())
                            }
                        },
                        enabled = enabled,
                    )
                    Text(
                        text = "Select All",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                columns.sortedBy { it.columnIndex }.forEach { column ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    ) {
                        Checkbox(
                            checked = column.originalName in selectedColumns,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onSelectionChanged(selectedColumns + column.originalName)
                                } else {
                                    onSelectionChanged(selectedColumns - column.originalName)
                                }
                            },
                            enabled = enabled,
                        )
                        Text(
                            text = column.originalName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Editor for attribute column mappings. Ticking a column maps it to an attribute type; a column can carry
 * several mappings (e.g. its raw value as one attribute, and a fixed `excluded` label when a pattern
 * matches), each with its own optional extraction, fixed value and row conditions.
 */
@Composable
internal fun AttributeMappingsEditor(
    columns: List<CsvColumn>,
    // Every column of the file: a mapping's row conditions may test any of them, not only the columns
    // offered as attributes.
    conditionColumns: List<CsvColumn>,
    mappings: List<AttributeColumnMapping>,
    onMappingsChanged: (List<AttributeColumnMapping>) -> Unit,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    firstRow: CsvRow?,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    if (mappings.isEmpty()) {
                        "None configured (click to expand)"
                    } else {
                        "${mappings.size} attribute(s) configured"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.sortedBy { it.columnIndex }.forEach { column ->
                    val columnName = column.originalName
                    // Indices into [mappings], so an edit changes exactly one mapping even when the column
                    // carries several.
                    val columnMappings = mappings.withIndex().filter { it.value.columnName == columnName }
                    val sampleValue =
                        firstRow
                            ?.values
                            ?.getOrNull(column.columnIndex)
                            .orEmpty()

                    AttributeColumnMappingRow(
                        columnName = columnName,
                        sampleValue = sampleValue,
                        columnMappings = columnMappings,
                        columns = conditionColumns,
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = enabled,
                        onEnabledChanged = { checked ->
                            if (checked) {
                                onMappingsChanged(
                                    mappings + AttributeColumnMapping(columnName = columnName, attributeTypeName = columnName),
                                )
                            } else {
                                onMappingsChanged(mappings.filter { it.columnName != columnName })
                            }
                        },
                        onAddMapping = {
                            onMappingsChanged(mappings + AttributeColumnMapping(columnName = columnName, attributeTypeName = columnName))
                        },
                        onMappingChanged = { index, updated ->
                            onMappingsChanged(mappings.mapIndexed { i, m -> if (i == index) updated else m })
                        },
                        onMappingRemoved = { index -> onMappingsChanged(mappings.filterIndexed { i, _ -> i != index }) },
                    )
                }
            }
        }
    }
}

/**
 * One CSV column's attribute mappings: a checkbox that maps the column, then one block per mapping.
 */
@Composable
private fun AttributeColumnMappingRow(
    columnName: String,
    sampleValue: String,
    columnMappings: List<IndexedValue<AttributeColumnMapping>>,
    columns: List<CsvColumn>,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onAddMapping: () -> Unit,
    onMappingChanged: (Int, AttributeColumnMapping) -> Unit,
    onMappingRemoved: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = columnMappings.isNotEmpty(),
                onCheckedChange = onEnabledChanged,
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = columnName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sampleValue.isNotBlank()) {
                    Text(
                        text = "Sample: $sampleValue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = columnMappings.isNotEmpty(),
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp)) {
                columnMappings.forEach { (index, mapping) ->
                    AttributeMappingDetails(
                        mapping = mapping,
                        onChanged = { onMappingChanged(index, it) },
                        onRemoved = { onMappingRemoved(index) }.takeIf { columnMappings.size > 1 },
                        columns = columns,
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = enabled,
                    )
                }
                TextButton(onClick = onAddMapping, enabled = enabled) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Add another attribute from this column")
                }
            }
        }
    }
}

/** One mapping: its attribute type, unique-identifier flag and (collapsed by default) value rules. */
@Composable
private fun AttributeMappingDetails(
    mapping: AttributeColumnMapping,
    onChanged: (AttributeColumnMapping) -> Unit,
    onRemoved: (() -> Unit)?,
    columns: List<CsvColumn>,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
) {
    val hasRules = mapping.extraction != null || mapping.emitWhenMatched != null || mapping.conditions.isNotEmpty()
    var showRules by remember { mutableStateOf(hasRules) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AttributeTypeField(
                value = mapping.attributeTypeName,
                onValueChange = { onChanged(mapping.copy(attributeTypeName = it)) },
                existingTypes = existingAttributeTypes,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            if (onRemoved != null) {
                IconButton(onClick = onRemoved, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove attribute mapping")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = mapping.isUniqueIdentifier,
                onCheckedChange = { onChanged(mapping.copy(isUniqueIdentifier = it)) },
                enabled = enabled,
            )
            Column {
                Text(
                    text = "Use as unique identifier",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Detects duplicates across multiple imports",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showRules, onCheckedChange = { showRules = it }, enabled = enabled)
            Text("Only for some rows / transform the value", style = MaterialTheme.typography.bodySmall)
        }
        if (showRules) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                ExtractionEditor(
                    label = "Extract with a pattern (rows it doesn't match get no attribute)",
                    extraction = mapping.extraction,
                    onChange = { onChanged(mapping.copy(extraction = it)) },
                    enabled = enabled,
                )
                OutlinedTextField(
                    value = mapping.emitWhenMatched.orEmpty(),
                    onValueChange = { onChanged(mapping.copy(emitWhenMatched = it.ifEmpty { null })) },
                    label = { Text("Fixed value instead (blank = the extracted text)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled && mapping.extraction != null,
                )
                RowConditionsEditor(
                    conditions = mapping.conditions,
                    onConditionsChanged = { onChanged(mapping.copy(conditions = it)) },
                    columns = columns,
                    enabled = enabled,
                    title = "Only on rows where (all must match)",
                )
            }
        }
    }
}
