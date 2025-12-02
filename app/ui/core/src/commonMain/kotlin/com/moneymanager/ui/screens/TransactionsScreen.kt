@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountTransactionsScreen(
    accountId: Long,
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    assetRepository: AssetRepository,
) {
    val allAccounts by accountRepository.getAllAccounts().collectAsState(initial = emptyList())
    val allTransactions by transactionRepository.getAllTransactions().collectAsState(initial = emptyList())
    val assets by assetRepository.getAllAssets().collectAsState(initial = emptyList())

    // Selected account state - default to the provided accountId
    var selectedAccountId by remember { mutableStateOf(accountId) }

    // Get running balances for the selected account
    val runningBalances by transactionRepository.getRunningBalanceByAccount(selectedAccountId)
        .collectAsState(initial = emptyList())

    // Build a map of transactionId -> full Transaction for additional details
    val transactionMap = allTransactions.associateBy { it.id }

    // Get unique asset IDs from running balances for this account
    val accountAssetIds = runningBalances.map { it.assetId }.distinct()
    val accountAssets = assets.filter { it.id in accountAssetIds }

    // Selected asset state - default to first asset if available
    var selectedAssetId by remember { mutableStateOf<Long?>(null) }

    // Update selected asset when account assets change
    LaunchedEffect(accountAssets) {
        if (accountAssets.isNotEmpty()) {
            // If currently selected asset exists in the new account's assets, keep it
            // Otherwise, select the first available asset
            if (selectedAssetId == null || accountAssets.none { it.id == selectedAssetId }) {
                selectedAssetId = accountAssets.first().id
            }
        } else {
            selectedAssetId = null
        }
    }

    // Filter running balances by selected asset
    val filteredRunningBalances =
        selectedAssetId?.let { assetId ->
            runningBalances.filter { it.assetId == assetId }
        } ?: emptyList()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Account Picker - Buttons
        if (allAccounts.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allAccounts.forEach { account ->
                    FilterChip(
                        selected = selectedAccountId == account.id,
                        onClick = { selectedAccountId = account.id },
                        label = { Text(account.name) },
                    )
                }
            }
        }

        // Asset Picker - Buttons
        if (accountAssets.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                accountAssets.forEach { asset ->
                    FilterChip(
                        selected = selectedAssetId == asset.id,
                        onClick = { selectedAssetId = asset.id },
                        label = { Text(asset.name) },
                    )
                }
            }
        }

        if (runningBalances.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No transactions yet for this account.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (filteredRunningBalances.isEmpty() && selectedAssetId != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No transactions for selected asset.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredRunningBalances) { runningBalance ->
                    val transaction = transactionMap[runningBalance.transactionId]
                    AccountTransactionCard(
                        runningBalance = runningBalance,
                        transaction = transaction,
                        currentAccountId = selectedAccountId,
                        accounts = allAccounts,
                        assets = assets,
                    )
                }
            }
        }
    }
}

@Composable
fun AccountTransactionCard(
    runningBalance: TransactionWithRunningBalance,
    transaction: Transaction?,
    currentAccountId: Long,
    accounts: List<Account>,
    assets: List<Asset>,
) {
    val sourceAccount = transaction?.let { accounts.find { a -> a.id == it.sourceAccountId } }
    val targetAccount = transaction?.let { accounts.find { a -> a.id == it.targetAccountId } }
    val asset = assets.find { it.id == runningBalance.assetId }

    // Determine the other account based on transaction direction
    val isOutgoing = runningBalance.transactionAmount < 0
    val otherAccount = if (isOutgoing) targetAccount else sourceAccount

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left column: Account and date info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (isOutgoing) {
                            "To: ${otherAccount?.name ?: "Unknown"}"
                        } else {
                            "From: ${otherAccount?.name ?: "Unknown"}"
                        },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val dateTime = runningBalance.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "${dateTime.date} ${dateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Middle column: Transaction amount
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = String.format("%+.2f", runningBalance.transactionAmount),
                    style = MaterialTheme.typography.titleLarge,
                    color =
                        if (runningBalance.transactionAmount >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Text(
                    text = asset?.name ?: "Unknown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Right column: Running balance
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format("%.2f", runningBalance.runningBalance),
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (runningBalance.runningBalance >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Text(
                    text = "Balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TransactionCard(
    transaction: Transaction,
    accounts: List<Account>,
    assets: List<Asset>,
) {
    val sourceAccount = accounts.find { it.id == transaction.sourceAccountId }
    val targetAccount = accounts.find { it.id == transaction.targetAccountId }
    val asset = assets.find { it.id == transaction.assetId }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        text = "${sourceAccount?.name ?: "Unknown"} → ${targetAccount?.name ?: "Unknown"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val dateTime = transaction.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        text = "${dateTime.date} ${dateTime.time}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format("%.2f", transaction.amount),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = asset?.name ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntryDialog(
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    assetRepository: AssetRepository,
    accounts: List<Account>,
    assets: List<Asset>,
    preSelectedSourceAccountId: Long? = null,
    onDismiss: () -> Unit,
) {
    var sourceAccountId by remember { mutableStateOf(preSelectedSourceAccountId) }
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
    var assetId by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var showCreateAssetDialog by remember { mutableStateOf(false) }
    var creatingForSource by remember { mutableStateOf(true) }

    var sourceAccountExpanded by remember { mutableStateOf(false) }
    var targetAccountExpanded by remember { mutableStateOf(false) }
    var assetExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Transaction") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Source Account Dropdown
                ExposedDropdownMenuBox(
                    expanded = sourceAccountExpanded,
                    onExpandedChange = { sourceAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value = accounts.find { it.id == sourceAccountId }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("From Account") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        enabled = !isSaving,
                    )
                    ExposedDropdownMenu(
                        expanded = sourceAccountExpanded,
                        onDismissRequest = { sourceAccountExpanded = false },
                    ) {
                        accounts.filter { it.id != targetAccountId }.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    sourceAccountId = account.id
                                    sourceAccountExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = true
                                showCreateAccountDialog = true
                                sourceAccountExpanded = false
                            },
                        )
                    }
                }

                // Target Account Dropdown
                ExposedDropdownMenuBox(
                    expanded = targetAccountExpanded,
                    onExpandedChange = { targetAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value = accounts.find { it.id == targetAccountId }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("To Account") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        enabled = !isSaving,
                    )
                    ExposedDropdownMenu(
                        expanded = targetAccountExpanded,
                        onDismissRequest = { targetAccountExpanded = false },
                    ) {
                        accounts.filter { it.id != sourceAccountId }.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    targetAccountId = account.id
                                    targetAccountExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = false
                                showCreateAccountDialog = true
                                targetAccountExpanded = false
                            },
                        )
                    }
                }

                // Asset Dropdown
                ExposedDropdownMenuBox(
                    expanded = assetExpanded,
                    onExpandedChange = { assetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = assets.find { it.id == assetId }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Asset/Currency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = assetExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        enabled = !isSaving,
                    )
                    ExposedDropdownMenu(
                        expanded = assetExpanded,
                        onDismissRequest = { assetExpanded = false },
                    ) {
                        assets.forEach { asset ->
                            DropdownMenuItem(
                                text = { Text(asset.name) },
                                onClick = {
                                    assetId = asset.id
                                    assetExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("+ Create New Asset") },
                            onClick = {
                                showCreateAssetDialog = true
                                assetExpanded = false
                            },
                        )
                    }
                }

                // Amount Input
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
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
                    when {
                        sourceAccountId == null -> errorMessage = "Please select a source account"
                        targetAccountId == null -> errorMessage = "Please select a target account"
                        sourceAccountId == targetAccountId -> errorMessage = "Source and target accounts must be different"
                        assetId == null -> errorMessage = "Please select an asset"
                        amount.isBlank() -> errorMessage = "Amount is required"
                        amount.toDoubleOrNull() == null -> errorMessage = "Invalid amount"
                        amount.toDouble() <= 0 -> errorMessage = "Amount must be greater than 0"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val now = Clock.System.now()
                                    val newTransaction =
                                        Transaction(
                                            sourceAccountId = sourceAccountId!!,
                                            targetAccountId = targetAccountId!!,
                                            assetId = assetId!!,
                                            amount = amount.toDouble(),
                                            timestamp = now,
                                        )
                                    transactionRepository.createTransaction(newTransaction)
                                    onDismiss()
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to create transaction: ${e.message}" }
                                    errorMessage = "Failed to create transaction: ${e.message}"
                                    isSaving = false
                                }
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )

    // Account Creation Dialog
    if (showCreateAccountDialog) {
        CreateAccountDialogInline(
            accountRepository = accountRepository,
            onAccountCreated = { accountId ->
                if (creatingForSource) {
                    sourceAccountId = accountId
                } else {
                    targetAccountId = accountId
                }
                showCreateAccountDialog = false
            },
            onDismiss = { showCreateAccountDialog = false },
        )
    }

    // Asset Creation Dialog
    if (showCreateAssetDialog) {
        CreateAssetDialogInline(
            assetRepository = assetRepository,
            onAssetCreated = { newAssetId ->
                assetId = newAssetId
                showCreateAssetDialog = false
            },
            onDismiss = { showCreateAssetDialog = false },
        )
    }
}

@Composable
fun CreateAccountDialogInline(
    accountRepository: AccountRepository,
    onAccountCreated: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Account") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
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
                    if (name.isBlank()) {
                        errorMessage = "Account name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val now = Clock.System.now()
                                val newAccount =
                                    Account(
                                        name = name.trim(),
                                        openingDate = now,
                                    )
                                val accountId = accountRepository.createAccount(newAccount)
                                onAccountCreated(accountId)
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to create account: ${e.message}" }
                                errorMessage = "Failed to create account: ${e.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun CreateAssetDialogInline(
    assetRepository: AssetRepository,
    onAssetCreated: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Asset") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Asset Name (e.g., USD, EUR)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
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
                    if (name.isBlank()) {
                        errorMessage = "Asset name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val assetId = assetRepository.upsertAssetByName(name.trim())
                                onAssetCreated(assetId)
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to create asset: ${e.message}" }
                                errorMessage = "Failed to create asset: ${e.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}
