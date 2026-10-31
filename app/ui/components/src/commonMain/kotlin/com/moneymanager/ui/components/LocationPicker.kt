@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.calendar.HolidayCalendar

/**
 * Picks a location: the holiday calendar whose business days a business-day reconcile window counts. Searchable by
 * code (`GBLO`), name or country. [noneLabel] names the choice of no location of its own (an account inheriting the
 * database's default, or a database without a default); null offers no such choice.
 */
@Composable
fun LocationPicker(
    calendars: List<HolidayCalendar>,
    selectedCode: String?,
    onLocationSelected: (String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filtered =
        remember(calendars, searchQuery) {
            if (searchQuery.isBlank()) {
                calendars
            } else {
                calendars.filter { calendar ->
                    calendar.id.contains(searchQuery, ignoreCase = true) ||
                        calendar.name.contains(searchQuery, ignoreCase = true) ||
                        calendar.countries.any { it.equals(searchQuery.trim(), ignoreCase = true) }
                }
            }
        }
    val selected = calendars.find { it.id == selectedCode }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value =
                if (expanded) {
                    searchQuery
                } else {
                    selected?.label ?: selectedCode ?: noneLabel.orEmpty()
                },
            onValueChange = { searchQuery = it },
            label = { Text(label) },
            placeholder = { Text("Type to search...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
            // A code no calendar answers to fails the next business-day import, so show it.
            isError = selectedCode != null && selected == null,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            noneLabel?.let { none ->
                DropdownMenuItem(
                    text = { Text(none) },
                    onClick = {
                        onLocationSelected(null)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
            filtered.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar.label) },
                    onClick = {
                        onLocationSelected(calendar.id)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
        }
    }
}
