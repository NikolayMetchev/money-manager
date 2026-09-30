@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.onEnterKeyDown

/**
 * A reusable account picker component with search and inline account creation.
 *
 * Features:
 * - Fetches accounts from repository (auto-updates when accounts change)
 * - Searchable dropdown with type-to-filter
 * - "Create New Account" option at top of dropdown
 * - Optional account exclusion (e.g., to prevent selecting same source and target)
 *
 * @param selectedAccountId The currently selected account ID, or null if none selected
 * @param onAccountSelected Callback invoked when an account is selected
 * @param label The label text displayed on the dropdown
 * @param accountRepository Repository to fetch accounts and create new ones
 * @param categoryRepository Repository needed for account creation (accounts have categories)
 * @param personRepository Repository needed for account creation (accounts can have owners)
 * @param enabled Whether the picker is enabled
 * @param excludeAccountId Optional account ID to exclude from the list (e.g., the other account in a transfer)
 * @param isError Whether to show error state (red outline)
 * @param accountFilter Restricts which existing accounts are offered (e.g. only real, non-shadow accounts)
 */
@Composable
fun AccountPicker(
    selectedAccountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    label: String,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    enabled: Boolean = true,
    excludeAccountId: AccountId? = null,
    isError: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    accountFilter: (Account) -> Boolean = { true },
) {
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }

    // Shadow accounts hold a reconciliation source's copy of the data (e.g. Koinly's), not real money, so
    // they're hidden by default; the tickbox in the menu brings them back.
    val shadowAccountIds: Set<AccountId> by produceState(emptySet(), accounts) {
        value = accountRepository.getAccountIdsByAttribute(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)
    }
    var hideShadowAccounts by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateAccountDialog by remember { mutableStateOf(false) }
    // The name to pre-fill the create dialog with (the substring the user typed, if any).
    var createAccountInitialName by remember { mutableStateOf("") }

    val filteredAccounts =
        remember(accounts, searchQuery, excludeAccountId, accountFilter, hideShadowAccounts, shadowAccountIds) {
            val available =
                accounts.filter {
                    it.id != excludeAccountId && accountFilter(it) && !(hideShadowAccounts && it.id in shadowAccountIds)
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
            // Editable while expanded so the user can type to filter (like the currency picker);
            // shows the selected account name when collapsed.
            value = if (expanded) searchQuery else displayValue,
            onValueChange = { searchQuery = it },
            label = { Text(label) },
            placeholder = { Text("Type to search...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .let { if (onSubmit != null) it.onEnterKeyDown(onSubmit) else it },
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
            val trimmedQuery = searchQuery.trim()
            val createLabel =
                if (trimmedQuery.isNotEmpty()) "+ Create \"$trimmedQuery\"" else "+ Create New Account"
            DropdownMenuItem(
                text = { Text(createLabel) },
                onClick = {
                    // Pre-fill the create dialog with whatever the user typed so a not-found name
                    // becomes a one-click new account.
                    createAccountInitialName = trimmedQuery
                    showCreateAccountDialog = true
                    expanded = false
                    searchQuery = ""
                },
            )
            // Only where it does something: the caller's own filter may already drop every shadow account.
            if (accounts.any { it.id in shadowAccountIds && it.id != excludeAccountId && accountFilter(it) }) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hideShadowAccounts, onCheckedChange = null)
                            Text("Hide reconciliation accounts")
                        }
                    },
                    onClick = { hideShadowAccounts = !hideShadowAccounts },
                )
            }
            HorizontalDivider()
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
        }
    }

    if (showCreateAccountDialog) {
        CreateAccountDialog(
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            initialName = createAccountInitialName,
            onDismiss = { showCreateAccountDialog = false },
            onAccountCreated = { accountId ->
                onAccountSelected(accountId)
            },
        )
    }
}
