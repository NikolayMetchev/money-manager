@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.AccountMergeRequest
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.presenter.accounts.AccountsEvent
import com.moneymanager.presenter.accounts.presentAccounts
import com.moneymanager.presenter.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.query.AccountQuery
import com.moneymanager.ui.components.CreateAccountDialog
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.components.EditAccountDialog
import com.moneymanager.ui.components.MultiSelectFilterDropdown
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.launch

private val logger = Logger.withTag("AccountsScreen")

@Composable
fun AccountsScreen(
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    attributeTypeRepository: AttributeTypeReadRepository,
    categoryRepository: CategoryReadRepository,
    transactionRepository: TransactionReadRepository,
    personRepository: PersonReadRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    maintenance: Maintenance,
    scrollToAccountId: AccountId?,
    onAccountClick: (Account) -> Unit,
    onAuditClick: (Account) -> Unit = {},
) {
    // The filters and what they keep live in the shared presenter (the TUI's Accounts tab renders the same
    // model); this screen adds the dialogs, categories and scroll position.
    val accountQuery =
        remember(
            accountRepository,
            accountAttributeRepository,
            transactionRepository,
            personRepository,
            personAccountOwnershipRepository,
        ) {
            AccountQuery(
                accountRepository = accountRepository,
                accountAttributeRepository = accountAttributeRepository,
                transactionRepository = transactionRepository,
                personRepository = personRepository,
                personAccountOwnershipRepository = personAccountOwnershipRepository,
            )
        }
    val snapshots = remember(accountQuery) { accountQuery.snapshot() }
    val model = presentAccounts(snapshots)
    val categories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        categoryRepository.getAllCategories()
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<Account?>(null) }

    val accounts = model.all
    val people = model.people
    val availableAssets = model.availableAssets
    val displayedAccounts = model.displayed

    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Your Accounts",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = { showCreateDialog = true }) {
                Text("+ Add Account")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No accounts yet. Add your first account!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OutlinedTextField(
                value = model.filter.nameText,
                onValueChange = { model.eventSink(AccountsEvent.SetName(it)) },
                label = { Text("Search accounts") },
                placeholder = { Text("Type to search...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (people.isNotEmpty()) {
                MultiSelectFilterDropdown(
                    items = people,
                    selectedKeys = model.filter.ownerIds,
                    itemKey = { it.id },
                    itemLabel = { it.fullName },
                    itemNoun = "owner",
                    onSelectionChange = { model.eventSink(AccountsEvent.SetOwners(it)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (availableAssets.isNotEmpty()) {
                MultiSelectFilterDropdown(
                    items = availableAssets,
                    selectedKeys = model.filter.assetIds,
                    itemKey = { it.id },
                    itemLabel = { "${it.code} — ${it.name}" },
                    itemNoun = "asset",
                    onSelectionChange = { model.eventSink(AccountsEvent.SetAssets(it)) },
                    selectedLabel = { it.code },
                    searchText = { "${it.code} ${it.name}" },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (model.shadowAccountCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = model.filter.includeShadow,
                        onCheckedChange = { model.eventSink(AccountsEvent.SetIncludeShadow(it)) },
                    )
                    Text("Show reconciliation accounts (${model.shadowAccountCount})")
                }
            }

            if (displayedAccounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No accounts match the current filters.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val lazyListState = rememberLazyListState()

            // Scroll to the specified account when navigating back
            LaunchedEffect(scrollToAccountId, displayedAccounts) {
                if (scrollToAccountId != null && displayedAccounts.isNotEmpty()) {
                    val index = displayedAccounts.indexOfFirst { it.account.id == scrollToAccountId }
                    if (index >= 0) {
                        lazyListState.animateScrollToItem(index)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedAccounts, key = { it.account.id.id }) { entry ->
                        val account = entry.account
                        AccountCard(
                            account = account,
                            category = categoriesById[account.categoryId],
                            balances = entry.balances,
                            owners = entry.owners,
                            accountRepository = accountRepository,
                            maintenance = maintenance,
                            onClick = { onAccountClick(account) },
                            onEditClick = { accountToEdit = account },
                            onAuditClick = { onAuditClick(account) },
                        )
                    }
                }
                VerticalScrollbarForLazyList(
                    lazyListState = lazyListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateAccountDialog(
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            onDismiss = { showCreateDialog = false },
            existingNames = accounts.map { it.account.name }.toSet(),
        )
    }

    val currentAccountToEdit = accountToEdit
    if (currentAccountToEdit != null) {
        EditAccountDialog(
            account = currentAccountToEdit,
            accountAttributeRepository = accountAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            onDismiss = { accountToEdit = null },
            existingNames = accounts.filter { it.account.id != currentAccountToEdit.id }.map { it.account.name }.toSet(),
        )
    }
}

@Composable
fun AccountCard(
    account: Account,
    category: Category?,
    balances: List<Money>,
    owners: List<Person>,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onAuditClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("account-card-${account.id.id}")
                .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (category != null && category.id != -1L) {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (owners.isNotEmpty()) {
                        Text(
                            text = "Owners: ${owners.joinToString(", ") { it.fullName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onEditClick,
                ) {
                    Text(
                        text = "✏️",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = onAuditClick,
                ) {
                    Text(
                        text = "📋",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                ) {
                    Text(
                        text = "🗑️",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (balances.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                balances.forEach { balance ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = balance.asset.code,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatAmount(balance),
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                when {
                                    balance.isPositive() -> MaterialTheme.colorScheme.primary
                                    balance.isNegative() -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                    if (balance != balances.last()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "No transactions yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            account = account,
            accountRepository = accountRepository,
            maintenance = maintenance,
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun DeleteAccountDialog(
    account: Account,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    onDismiss: () -> Unit,
) {
    var transferCount by remember { mutableStateOf<Long?>(null) }
    var conflictingTransfers by remember { mutableStateOf(emptyList<Transfer>()) }
    var selectedTargetAccount by remember { mutableStateOf<Account?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var targetSearchQuery by remember { mutableStateOf("") }
    val importEngine = LocalImportEngine.current

    val allAccounts by accountRepository
        .getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val otherAccounts = allAccounts.filter { it.id != account.id }
    val filteredTargetAccounts =
        if (targetSearchQuery.isBlank()) {
            otherAccounts
        } else {
            otherAccounts.filter { it.name.contains(targetSearchQuery, ignoreCase = true) }
        }

    LaunchedEffect(Unit) {
        transferCount = accountRepository.countTransfersByAccount(account.id)
    }

    LaunchedEffect(selectedTargetAccount) {
        val target = selectedTargetAccount
        conflictingTransfers =
            if (target != null) {
                accountRepository.getTransfersBetweenAccounts(account.id, target.id)
            } else {
                emptyList()
            }
    }

    val hasTransactions = (transferCount ?: 0) > 0
    val hasConflicts = conflictingTransfers.isNotEmpty()
    val canDelete = !hasTransactions || (selectedTargetAccount != null && !hasConflicts)
    val noOtherAccounts = hasTransactions && otherAccounts.isEmpty()

    DestructiveConfirmDialog(
        title = if (hasTransactions) "Merge Account?" else "Delete Account?",
        targetName = account.name,
        confirmLabel = if (hasTransactions) "Merge" else "Delete",
        confirmEnabled = canDelete && !noOtherAccounts && transferCount != null,
        failureMessage = "Failed to remove account",
        body = { isBusy ->
            if (transferCount == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
            } else if (hasTransactions) {
                Text(
                    text =
                        "\"${account.name}\" will be deleted and its $transferCount transaction(s) merged into the " +
                            "surviving account you choose below. You can undo this later from the surviving account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (noOtherAccounts) {
                    Text(
                        text = "No other accounts available. Create another account first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { if (!isBusy) dropdownExpanded = !dropdownExpanded },
                    ) {
                        OutlinedTextField(
                            // Editable while expanded so the user can type to filter; shows the
                            // selected account name when collapsed.
                            value = if (dropdownExpanded) targetSearchQuery else (selectedTargetAccount?.name ?: ""),
                            onValueChange = {
                                targetSearchQuery = it
                                dropdownExpanded = true
                            },
                            label = { Text("Merge into (surviving account)") },
                            placeholder = { Text("Type to search...") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                            enabled = !isBusy,
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = {
                                dropdownExpanded = false
                                targetSearchQuery = ""
                            },
                        ) {
                            filteredTargetAccounts.forEach { targetAccount ->
                                DropdownMenuItem(
                                    text = { Text(targetAccount.name) },
                                    onClick = {
                                        selectedTargetAccount = targetAccount
                                        dropdownExpanded = false
                                        targetSearchQuery = ""
                                    },
                                )
                            }
                        }
                    }
                    if (hasConflicts) {
                        Text(
                            text =
                                "Cannot merge into \"${selectedTargetAccount?.name}\": " +
                                    "${conflictingTransfers.size} transaction(s) between these accounts " +
                                    "would become invalid. Choose a different account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        conflictingTransfers.forEach { transfer ->
                            Text(
                                text = "${transfer.description} - ${formatAmount(transfer.amount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onConfirm = {
            if (hasTransactions) {
                importEngine.import(
                    ImportBatch.manualEdits(
                        accountMerges =
                            listOf(
                                AccountMergeRequest(
                                    deletedId = account.id,
                                    survivingId = selectedTargetAccount!!.id,
                                ),
                            ),
                    ),
                )
                maintenance.fullRefreshMaterializedViews()
            } else {
                importEngine.import(
                    ImportBatch.manualEdits(
                        accounts =
                            listOf(
                                ImportAccountIntent(
                                    key = LocalAccountKey("delete"),
                                    source = Source.Manual,
                                    operation = ImportOperation.DELETE,
                                    existingId = account.id,
                                ),
                            ),
                    ),
                )
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun UnmergeAccountDialog(
    merge: AccountMerge,
    survivingAccountName: String,
    maintenance: Maintenance,
    onDismiss: () -> Unit,
) {
    var isUnmerging by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    AlertDialog(
        onDismissRequest = { if (!isUnmerging) onDismiss() },
        icon = {
            Text(
                text = "↩️",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        title = { Text("Undo Merge?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        "This will recreate \"${merge.deletedAccountName}\" and move its " +
                            "${merge.transferCount} transaction(s) back out of \"$survivingAccountName\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isUnmerging = true
                    errorMessage = null
                    scope.launch {
                        try {
                            importEngine.import(ImportBatch.manualEdits(accountUnmerges = listOf(merge.id)))
                            maintenance.fullRefreshMaterializedViews()
                            onDismiss()
                        } catch (expected: Exception) {
                            logger.e(expected) { "Failed to undo merge: ${expected.message}" }
                            errorMessage = "Failed to undo merge: ${expected.message}"
                            isUnmerging = false
                        }
                    }
                },
                enabled = !isUnmerging,
            ) {
                if (isUnmerging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Undo merge")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isUnmerging,
            ) {
                Text("Cancel")
            }
        },
    )
}
