@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A searchable multi-select filter dropdown: a checkbox row per item plus an "All …" row that clears
 * the selection. Collapsed it shows a selection summary; expanded the text field becomes a search box
 * (like [AccountPicker]/[CurrencyPicker]).
 *
 * Every visible string is derived from [itemNoun] ("owner" → "Filter by owner", "All owners",
 * "3 owners"), so a call site normally passes only the data and the noun.
 *
 * @param itemNounPlural the plural of [itemNoun] — defaults to `itemNoun + "s"`, so only irregular
 *   nouns ("category", "entry") need to pass it.
 * @param itemKey stable identity of an item; the selection is a set of these.
 * @param itemLabel the text of an item's row in the menu.
 * @param selectedLabel the collapsed summary when exactly one item is selected — defaults to
 *   [itemLabel], and differs where the row carries more than the name (e.g. "GBP — Pound" vs "GBP").
 * @param searchText the text the typed query is matched against — defaults to [itemLabel].
 */
@Composable
fun <T, K> MultiSelectFilterDropdown(
    items: List<T>,
    selectedKeys: Set<K>,
    itemKey: (T) -> K,
    itemLabel: (T) -> String,
    itemNoun: String,
    onSelectionChange: (Set<K>) -> Unit,
    modifier: Modifier = Modifier,
    itemNounPlural: String = "${itemNoun}s",
    selectedLabel: (T) -> String = itemLabel,
    searchText: (T) -> String = itemLabel,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val allLabel = "All $itemNounPlural"
    val selectionLabel =
        when (selectedKeys.size) {
            0 -> allLabel
            1 -> items.find { itemKey(it) in selectedKeys }?.let(selectedLabel) ?: "1 $itemNoun"
            else -> "${selectedKeys.size} $itemNounPlural"
        }

    val filteredItems =
        remember(items, searchQuery, searchText) {
            if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { searchText(it).contains(searchQuery, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            // Editable while expanded so the user can type to filter (like the account/currency pickers);
            // shows the current selection summary when collapsed.
            value = if (expanded) searchQuery else selectionLabel,
            onValueChange = { searchQuery = it },
            label = { Text("Filter by $itemNoun") },
            placeholder = { Text("Type to search...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = { onSelectionChange(emptySet()) },
            )
            filteredItems.forEach { item ->
                val key = itemKey(item)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedKeys.contains(key),
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(itemLabel(item))
                        }
                    },
                    onClick = {
                        onSelectionChange(
                            if (selectedKeys.contains(key)) selectedKeys - key else selectedKeys + key,
                        )
                    },
                )
            }
        }
    }
}
