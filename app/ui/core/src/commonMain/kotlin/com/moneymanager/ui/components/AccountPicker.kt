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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling

/**
 * A reusable account picker component with search and inline account creation.
 *
 * Features:
 * - Fetches accounts from repository (auto-updates when accounts change)
 * - Searchable dropdown with type-to-filter
 * - "Create New Account" option at bottom of dropdown
 * - Optional account exclusion (e.g., to prevent selecting same source and target)
 *
 * @param selectedAccountId The currently selected account ID, or null if none selected
 * @param onAccountSelected Callback invoked when an account is selected
 * @param label The label text displayed on the dropdown
 * @param accountRepository Repository to fetch accounts and create new ones
 * @param categoryRepository Repository needed for account creation (accounts have categories)
 * @param personRepository Repository needed for account creation (accounts can have owners)
 * @param personAccountOwnershipRepository Repository needed for account creation (accounts can have owners)
 * @param enabled Whether the picker is enabled
 * @param excludeAccountId Optional account ID to exclude from the list (e.g., the other account in a transfer)
 * @param isError Whether to show error state (red outline)
 */
@Composable
fun AccountPicker(
    selectedAccountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    label: String,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    enabled: Boolean = true,
    excludeAccountId: AccountId? = null,
    isError: Boolean = false,
) {
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateAccountDialog by remember { mutableStateOf(false) }

    val filteredAccounts =
        remember(accounts, searchQuery, excludeAccountId) {
            val available =
                if (excludeAccountId != null) {
                    accounts.filter { it.id != excludeAccountId }
                } else {
                    accounts
                }
            if (searchQuery.isBlank()) {
                available
            } else {
                available.filter { account ->
                    account.name.contains(searchQuery, ignoreCase = true)
                }
            }
        }

    val selectedAccount = accounts.find { it.id == selectedAccountId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        // Use a non-empty placeholder so tests can click on it (similar to "Uncategorized" in category dropdown)
        val displayValue = selectedAccount?.name ?: "Select..."
        OutlinedTextField(
            value = displayValue,
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            singleLine = true,
            isError = isError,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            filteredAccounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onAccountSelected(account.id)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("+ Create New Account") },
                onClick = {
                    showCreateAccountDialog = true
                    expanded = false
                    searchQuery = ""
                },
            )
        }
    }

    if (showCreateAccountDialog) {
        CreateAccountDialog(
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            onDismiss = { showCreateAccountDialog = false },
            onAccountCreated = { accountId ->
                onAccountSelected(accountId)
            },
        )
    }
}
