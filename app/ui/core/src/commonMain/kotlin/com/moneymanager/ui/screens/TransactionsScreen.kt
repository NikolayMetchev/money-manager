@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.clickable
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
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.launch
import kotlinx.datetime.*
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
    onAccountIdChange: (Long) -> Unit = {},
) {
    val allAccounts by accountRepository.getAllAccounts().collectAsState(initial = emptyList())
    val allTransactions by transactionRepository.getAllTransactions().collectAsState(initial = emptyList())
    val assets by assetRepository.getAllAssets().collectAsState(initial = emptyList())
    val accountBalances by transactionRepository.getAccountBalances().collectAsState(initial = emptyList())

    // Selected account state - default to the provided accountId
    var selectedAccountId by remember { mutableStateOf(accountId) }

    // Notify parent when selected account changes
    LaunchedEffect(selectedAccountId) {
        onAccountIdChange(selectedAccountId)
    }

    // Highlighted transaction state
    var highlightedTransactionId by remember { mutableStateOf<Long?>(null) }

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
        // Account Picker - Buttons with balances underneath in table format
        if (allAccounts.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row of account buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Empty space for asset name column
                    Spacer(modifier = Modifier.width(60.dp))

                    allAccounts.forEach { account ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            FilterChip(
                                selected = selectedAccountId == account.id,
                                onClick = { selectedAccountId = account.id },
                                label = { Text(account.name) },
                            )
                        }
                    }
                }

                // Get all unique assets from account balances
                val uniqueAssetIds = accountBalances.map { it.assetId }.distinct()

                // Row for each asset
                uniqueAssetIds.forEach { assetId ->
                    val asset = assets.find { it.id == assetId }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Asset name as row header
                        Text(
                            text = "${asset?.name ?: "?"}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(60.dp),
                        )

                        // Balance for each account
                        allAccounts.forEach { account ->
                            val balance =
                                accountBalances.find {
                                    it.accountId == account.id && it.assetId == assetId
                                }
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (balance != null) {
                                    Text(
                                        text = String.format("%.2f", balance.balance),
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (balance.balance >= 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                    )
                                } else {
                                    // Empty cell if account doesn't have this asset
                                    Text(
                                        text = "-",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
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
            // Column Headers
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Date/Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.2f),
                )
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.25f).padding(horizontal = 8.dp),
                )
                Text(
                    text = "Description",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.3f).padding(horizontal = 8.dp),
                )
                Text(
                    text = "Amount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(0.15f),
                )
                Text(
                    text = "Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(0.15f).padding(start = 8.dp),
                )
            }

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
                        isHighlighted = highlightedTransactionId == runningBalance.transactionId,
                        onAccountClick = { accountId ->
                            highlightedTransactionId = runningBalance.transactionId
                            selectedAccountId = accountId
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun AccountTransactionCard(
    runningBalance: TransactionWithRunningBalance,
    transaction: Transfer?,
    currentAccountId: Long,
    accounts: List<Account>,
    assets: List<Asset>,
    isHighlighted: Boolean = false,
    onAccountClick: (Long) -> Unit = {},
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
        colors =
            if (isHighlighted) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leftmost column: Date and time
            val dateTime = runningBalance.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            Column(modifier = Modifier.weight(0.2f)) {
                Text(
                    text = "${dateTime.date}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${dateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Second column: Account name (clickable)
            Text(
                text = otherAccount?.name ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (otherAccount != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier =
                    Modifier
                        .weight(0.25f)
                        .padding(horizontal = 8.dp)
                        .clickable(enabled = otherAccount != null) {
                            otherAccount?.id?.let { onAccountClick(it) }
                        },
            )

            // Third column: Description
            transaction?.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.3f).padding(horizontal = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(0.3f))
                }
            } ?: Spacer(modifier = Modifier.weight(0.3f))

            // Fourth column: Transaction amount
            Text(
                text = String.format("%+.2f", runningBalance.transactionAmount),
                style = MaterialTheme.typography.titleLarge,
                color =
                    if (runningBalance.transactionAmount >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(0.15f),
            )

            // Rightmost column: Running balance
            Text(
                text = String.format("%.2f", runningBalance.runningBalance),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (runningBalance.runningBalance >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(0.15f).padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun TransactionCard(
    transaction: Transfer,
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
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leftmost column: Date and time
            val dateTime = transaction.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            Column(modifier = Modifier.weight(0.2f)) {
                Text(
                    text = "${dateTime.date}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${dateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Second column: Accounts
            Text(
                text = "${sourceAccount?.name ?: "Unknown"} → ${targetAccount?.name ?: "Unknown"}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(0.3f).padding(horizontal = 8.dp),
            )

            // Third column: Description
            if (transaction.description.isNotBlank()) {
                Text(
                    text = transaction.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.35f).padding(horizontal = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(0.35f))
            }

            // Rightmost column: Amount
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(0.15f),
            ) {
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
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Date and time picker state
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    var selectedDate by remember { mutableStateOf(now.date) }
    var selectedHour by remember { mutableStateOf(now.hour) }
    var selectedMinute by remember { mutableStateOf(now.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

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

                // Date and Time Pickers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Date Picker
                    OutlinedTextField(
                        value = selectedDate.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            IconButton(
                                onClick = { showDatePicker = true },
                                enabled = !isSaving,
                            ) {
                                Text(
                                    text = "\uD83D\uDCC5",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = false,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )

                    // Time Picker
                    OutlinedTextField(
                        value = String.format("%02d:%02d", selectedHour, selectedMinute),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        trailingIcon = {
                            IconButton(
                                onClick = { showTimePicker = true },
                                enabled = !isSaving,
                            ) {
                                Text(
                                    text = "\uD83D\uDD54",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = false,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
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

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
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
                        description.isBlank() -> errorMessage = "Description is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    // Convert selected date and time to Instant
                                    val timestamp =
                                        selectedDate
                                            .atTime(selectedHour, selectedMinute, 0)
                                            .toInstant(TimeZone.currentSystemDefault())
                                    transactionRepository.createTransfer(
                                        timestamp = timestamp,
                                        description = description.trim(),
                                        sourceAccountId = sourceAccountId!!,
                                        targetAccountId = targetAccountId!!,
                                        assetId = assetId!!,
                                        amount = amount.toDouble(),
                                    )
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

    // Date Picker Dialog
    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    selectedDate
                        .atTime(0, 0)
                        .toInstant(TimeZone.UTC)
                        .toEpochMilliseconds(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            // Convert milliseconds to LocalDate
                            selectedDate =
                                Instant
                                    .fromEpochMilliseconds(millis)
                                    .toLocalDateTime(TimeZone.UTC)
                                    .date
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time Picker Dialog
    if (showTimePicker) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = selectedHour,
                initialMinute = selectedMinute,
                is24Hour = false,
            )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedHour = timePickerState.hour
                        selectedMinute = timePickerState.minute
                        showTimePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
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
