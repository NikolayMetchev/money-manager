@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.moneymanager.domain.model.csv.CsvColumn
import kotlinx.datetime.TimeZone

/**
 * Dropdown selector for CSV columns with sample value preview.
 */
@Composable
internal fun ColumnDropdown(
    columns: List<CsvColumn>,
    selectedColumn: String?,
    onColumnSelected: (String) -> Unit,
    label: String,
    sampleValue: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedColumn.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            isError = isError,
            supportingText =
                sampleValue?.let {
                    { Text("Sample: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            columns.sortedBy { it.columnIndex }.forEach { column ->
                DropdownMenuItem(
                    text = {
                        DropdownSelectionRow(
                            text = column.originalName,
                            selected = column.originalName == selectedColumn,
                        )
                    },
                    onClick = {
                        onColumnSelected(column.originalName)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Optional dropdown selector for CSV columns with "None" option.
 * Allows selecting no column (null) or a specific column.
 */
@Composable
internal fun OptionalColumnDropdown(
    columns: List<CsvColumn>,
    selectedColumn: String?,
    onColumnSelected: (String?) -> Unit,
    label: String,
    sampleValue: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedColumn ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            supportingText =
                sampleValue?.let {
                    { Text("Sample: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    DropdownSelectionRow(
                        text = "None",
                        selected = selectedColumn == null,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onColumnSelected(null)
                    expanded = false
                },
            )
            columns.sortedBy { it.columnIndex }.forEach { column ->
                DropdownMenuItem(
                    text = {
                        DropdownSelectionRow(
                            text = column.originalName,
                            selected = column.originalName == selectedColumn,
                        )
                    },
                    onClick = {
                        onColumnSelected(column.originalName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun DropdownSelectionRow(
    text: String,
    selected: Boolean,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = textColor,
        )
        if (selected) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Dropdown selector for timezone with search capability.
 * Uses kotlinx-datetime's TimeZone.availableZoneIds for multiplatform compatibility.
 */
@Composable
internal fun TimezonePicker(
    selectedTimezone: String,
    onTimezoneSelected: (String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Get all available timezone IDs (multiplatform via kotlinx-datetime)
    val allTimezones = remember { TimeZone.availableZoneIds.sorted() }

    // Filter timezones based on search query
    val filteredTimezones =
        remember(searchQuery) {
            if (searchQuery.isBlank()) {
                // Show common timezones first when no search
                val common =
                    listOf(
                        "Europe/London",
                        "UTC",
                        "America/New_York",
                        "America/Los_Angeles",
                        "Europe/Paris",
                        "Asia/Tokyo",
                    ).filter { it in allTimezones }
                common + (allTimezones - common.toSet()).take(20)
            } else {
                allTimezones.filter { it.contains(searchQuery, ignoreCase = true) }.take(50)
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = if (expanded) searchQuery else selectedTimezone,
            onValueChange = { searchQuery = it },
            readOnly = !expanded,
            label = { Text("Select Timezone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            placeholder = { if (expanded) Text("Type to search...") },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            filteredTimezones.forEach { tzId ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(tzId)
                            if (tzId == selectedTimezone) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        onTimezoneSelected(tzId)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
            if (filteredTimezones.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No timezones found", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}
