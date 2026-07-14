@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Category
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
import com.moneymanager.ui.components.CreateAccountDialog
import com.moneymanager.ui.components.EditAccountDialog
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

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
    // Use schema-error-aware collection for flows that may fail on old databases.
    // Flows are remembered so they subscribe once instead of re-querying on every recomposition.
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }
    val balances by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        transactionRepository.getAccountBalances()
    }
    val categories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        categoryRepository.getAllCategories()
    }
    val people by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getAllPeople()
    }
    val ownerships by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personAccountOwnershipRepository.getAllOwnerships()
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<Account?>(null) }
    var selectedOwnerIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var nameFilter by remember { mutableStateOf("") }
    var selectedAssetIds by remember { mutableStateOf<Set<AssetId>>(emptySet()) }

    // Drop selections for owners that no longer exist (e.g. a person was deleted).
    val availableOwnerIds = people.map { it.id.id }.toSet()
    LaunchedEffect(availableOwnerIds) {
        selectedOwnerIds = selectedOwnerIds intersect availableOwnerIds
    }

    val ownerIdsByAccount: Map<AccountId, Set<Long>> =
        remember(ownerships) {
            ownerships.groupBy({ it.accountId }, { it.personId.id }).mapValues { it.value.toSet() }
        }

    // Precompute per-account lookups once so each card avoids O(accounts)/O(balances) scans.
    val ownersByAccount: Map<AccountId, List<Person>> =
        remember(ownerships, people) {
            val peopleById = people.associateBy { it.id }
            ownerships
                .groupBy { it.accountId }
                .mapValues { (_, os) -> os.mapNotNull { peopleById[it.personId] } }
        }
    val balancesByAccount = remember(balances) { balances.groupBy { it.accountId } }
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    // Unique assets that appear in at least one account balance, sorted by code for stable display.
    val availableAssets: List<Asset> =
        remember(balances) {
            balances.map { it.balance.asset }.distinctBy { it.id }.sortedBy { it.code }
        }

    // Drop asset selections for assets that no longer have any balances.
    val availableAssetIds = availableAssets.map { it.id }.toSet()
    LaunchedEffect(availableAssetIds) {
        selectedAssetIds = selectedAssetIds intersect availableAssetIds
    }

    val displayedAccounts =
        remember(accounts, nameFilter, selectedOwnerIds, ownerIdsByAccount, selectedAssetIds, balancesByAccount) {
            accounts.filter { account ->
                val matchesName = nameFilter.isBlank() || account.name.contains(nameFilter, ignoreCase = true)
                val matchesOwner =
                    selectedOwnerIds.isEmpty() ||
                        ownerIdsByAccount[account.id].orEmpty().any { it in selectedOwnerIds }
                val matchesAsset =
                    selectedAssetIds.isEmpty() ||
                        balancesByAccount[account.id].orEmpty().any { it.balance.asset.id in selectedAssetIds }
                matchesName && matchesOwner && matchesAsset
            }
        }

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
                value = nameFilter,
                onValueChange = { nameFilter = it },
                label = { Text("Search accounts") },
                placeholder = { Text("Type to search...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (people.isNotEmpty()) {
                OwnerFilterDropdown(
                    people = people,
                    selectedOwnerIds = selectedOwnerIds,
                    onSelectionChange = { selectedOwnerIds = it },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (availableAssets.isNotEmpty()) {
                AssetFilterDropdown(
                    assets = availableAssets,
                    selectedAssetIds = selectedAssetIds,
                    onSelectionChange = { selectedAssetIds = it },
                )
                Spacer(modifier = Modifier.height(8.dp))
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
                    val index = displayedAccounts.indexOfFirst { it.id == scrollToAccountId }
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
                    items(displayedAccounts, key = { it.id.id }) { account ->
                        AccountCard(
                            account = account,
                            category = categoriesById[account.categoryId],
                            balances = balancesByAccount[account.id].orEmpty(),
                            owners = ownersByAccount[account.id].orEmpty(),
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
        )
    }
}

@Composable
private fun OwnerFilterDropdown(
    people: List<Person>,
    selectedOwnerIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectionLabel =
        when (selectedOwnerIds.size) {
            0 -> "All owners"
            1 -> people.find { it.id.id in selectedOwnerIds }?.fullName ?: "1 owner"
            else -> "${selectedOwnerIds.size} owners"
        }

    val filteredPeople =
        remember(people, searchQuery) {
            if (searchQuery.isBlank()) {
                people
            } else {
                people.filter { it.fullName.contains(searchQuery, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            // Editable while expanded so the user can type to filter (like the account/currency pickers);
            // shows the current selection summary when collapsed.
            value = if (expanded) searchQuery else selectionLabel,
            onValueChange = { searchQuery = it },
            label = { Text("Filter by owner") },
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
                text = { Text("All owners") },
                onClick = { onSelectionChange(emptySet()) },
            )
            filteredPeople.forEach { person ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedOwnerIds.contains(person.id.id),
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(person.fullName)
                        }
                    },
                    onClick = {
                        onSelectionChange(
                            if (selectedOwnerIds.contains(person.id.id)) {
                                selectedOwnerIds - person.id.id
                            } else {
                                selectedOwnerIds + person.id.id
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AssetFilterDropdown(
    assets: List<Asset>,
    selectedAssetIds: Set<AssetId>,
    onSelectionChange: (Set<AssetId>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectionLabel =
        when (selectedAssetIds.size) {
            0 -> "All assets"
            1 -> assets.find { it.id in selectedAssetIds }?.code ?: "1 asset"
            else -> "${selectedAssetIds.size} assets"
        }

    val filteredAssets =
        remember(assets, searchQuery) {
            if (searchQuery.isBlank()) {
                assets
            } else {
                assets.filter {
                    it.code.contains(searchQuery, ignoreCase = true) ||
                        it.name.contains(searchQuery, ignoreCase = true)
                }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            // Editable while expanded so the user can type to filter assets;
            // shows the current selection summary when collapsed.
            value = if (expanded) searchQuery else selectionLabel,
            onValueChange = { searchQuery = it },
            label = { Text("Filter by asset") },
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
                text = { Text("All assets") },
                onClick = { onSelectionChange(emptySet()) },
            )
            filteredAssets.forEach { asset ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedAssetIds.contains(asset.id),
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${asset.code} — ${asset.name}")
                        }
                    },
                    onClick = {
                        onSelectionChange(
                            if (selectedAssetIds.contains(asset.id)) {
                                selectedAssetIds - asset.id
                            } else {
                                selectedAssetIds + asset.id
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun AccountCard(
    account: Account,
    category: Category?,
    balances: List<AccountBalance>,
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
                            text = balance.balance.asset.code,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatAmount(balance.balance),
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                when {
                                    balance.balance.isPositive() -> MaterialTheme.colorScheme.primary
                                    balance.balance.isNegative() -> MaterialTheme.colorScheme.error
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
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var transferCount by remember { mutableStateOf<Long?>(null) }
    var conflictingTransfers by remember { mutableStateOf(emptyList<Transfer>()) }
    var selectedTargetAccount by remember { mutableStateOf<Account?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var targetSearchQuery by remember { mutableStateOf("") }
    val scope = rememberSchemaAwareCoroutineScope()
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

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = {
            Text(
                text = "⚠️",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        title = { Text(if (hasTransactions) "Merge Account?" else "Delete Account?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Are you sure you want to delete \"${account.name}\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
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
                            onExpandedChange = { if (!isDeleting) dropdownExpanded = !dropdownExpanded },
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
                                enabled = !isDeleting,
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
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
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
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
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
                        } catch (expected: Exception) {
                            logger.error(expected) { "Failed to remove account: ${expected.message}" }
                            errorMessage = "Failed to remove account: ${expected.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting && canDelete && !noOtherAccounts && transferCount != null,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (hasTransactions) "Merge" else "Delete")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
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
                            logger.error(expected) { "Failed to undo merge: ${expected.message}" }
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
