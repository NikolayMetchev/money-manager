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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.moneymanager.ui.screens.transactions.AttributeTypeField

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
 * Editor for attribute column mappings.
 * Allows mapping CSV columns to attribute types (existing or new).
 */
@Composable
internal fun AttributeMappingsEditor(
    columns: List<CsvColumn>,
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
                    val mapping = mappings.find { it.columnName == columnName }
                    val isEnabled = mapping != null
                    val attributeTypeName = mapping?.attributeTypeName ?: columnName
                    val isUniqueIdentifier = mapping?.isUniqueIdentifier ?: false
                    val sampleValue =
                        firstRow
                            ?.values
                            ?.getOrNull(column.columnIndex)
                            .orEmpty()

                    AttributeColumnMappingRow(
                        columnName = columnName,
                        sampleValue = sampleValue,
                        isEnabled = isEnabled,
                        attributeTypeName = attributeTypeName,
                        isUniqueIdentifier = isUniqueIdentifier,
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = enabled,
                        onEnabledChanged = { checked ->
                            if (checked) {
                                onMappingsChanged(
                                    mappings +
                                        AttributeColumnMapping(
                                            columnName = columnName,
                                            attributeTypeName = columnName,
                                        ),
                                )
                            } else {
                                onMappingsChanged(mappings.filter { it.columnName != columnName })
                            }
                        },
                        onAttributeTypeChanged = { newTypeName ->
                            onMappingsChanged(
                                mappings.map {
                                    if (it.columnName == columnName) {
                                        it.copy(attributeTypeName = newTypeName)
                                    } else {
                                        it
                                    }
                                },
                            )
                        },
                        onUniqueIdentifierChanged = { isUnique ->
                            onMappingsChanged(
                                mappings.map {
                                    if (it.columnName == columnName) {
                                        it.copy(isUniqueIdentifier = isUnique)
                                    } else {
                                        it
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Single row for mapping a CSV column to an attribute type.
 */
@Composable
private fun AttributeColumnMappingRow(
    columnName: String,
    sampleValue: String,
    isEnabled: Boolean,
    attributeTypeName: String,
    isUniqueIdentifier: Boolean,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onAttributeTypeChanged: (String) -> Unit,
    onUniqueIdentifierChanged: (Boolean) -> Unit,
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
                checked = isEnabled,
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

        // Show attribute type selector and unique identifier checkbox when enabled
        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp)) {
                AttributeTypeField(
                    value = attributeTypeName,
                    onValueChange = onAttributeTypeChanged,
                    existingTypes = existingAttributeTypes,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Unique identifier checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = isUniqueIdentifier,
                        onCheckedChange = onUniqueIdentifierChanged,
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
            }
        }
    }
}
