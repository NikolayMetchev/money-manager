@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling

/**
 * A reusable currency picker component with search and inline currency creation.
 *
 * Features:
 * - Fetches currencies from repository (auto-updates when currencies change)
 * - Searchable dropdown with type-to-filter (by code or name)
 * - "Create New Currency" option at bottom of dropdown
 *
 * @param selectedCurrencyId The currently selected currency ID, or null if none selected
 * @param onCurrencySelected Callback invoked when a currency is selected
 * @param label The label text displayed on the dropdown
 * @param currencyRepository Repository to fetch currencies and create new ones
 * @param modifier Optional modifier for the component
 * @param enabled Whether the picker is enabled
 * @param placeholder Placeholder text shown when dropdown is expanded
 */
@Composable
fun CurrencyPicker(
    selectedCurrencyId: CurrencyId?,
    onCurrencySelected: (CurrencyId) -> Unit,
    label: String,
    currencyRepository: CurrencyRepository,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Type to search...",
) {
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateCurrencyDialog by remember { mutableStateOf(false) }

    val filteredCurrencies =
        remember(currencies, searchQuery) {
            if (searchQuery.isBlank()) {
                currencies
            } else {
                currencies.filter { currency ->
                    currency.code.contains(searchQuery, ignoreCase = true) ||
                        currency.name.contains(searchQuery, ignoreCase = true)
                }
            }
        }

    val selectedCurrency = currencies.find { it.id == selectedCurrencyId }

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
                    selectedCurrency?.let { "${it.code} - ${it.name}" } ?: ""
                },
            onValueChange = { searchQuery = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            filteredCurrencies.forEach { currency ->
                DropdownMenuItem(
                    text = { Text("${currency.code} - ${currency.name}") },
                    onClick = {
                        onCurrencySelected(currency.id)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("+ Create New Currency") },
                onClick = {
                    showCreateCurrencyDialog = true
                    expanded = false
                    searchQuery = ""
                },
            )
        }
    }

    if (showCreateCurrencyDialog) {
        CreateCurrencyDialog(
            currencyRepository = currencyRepository,
            onCurrencyCreated = { currencyId ->
                onCurrencySelected(currencyId)
                showCreateCurrencyDialog = false
            },
            onDismiss = { showCreateCurrencyDialog = false },
        )
    }
}
