@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = logging()

/**
 * Screen size classification for responsive layouts.
 * Based on Material Design 3 window size classes.
 */
enum class ScreenSizeClass {
    /** Compact: phones in portrait (width < 600dp) */
    Compact,

    /** Medium: tablets in portrait, foldables (600dp <= width < 840dp) */
    Medium,

    /** Expanded: tablets in landscape, desktops (width >= 840dp) */
    Expanded,
    ;

    companion object {
        fun fromWidth(width: androidx.compose.ui.unit.Dp): ScreenSizeClass =
            when {
                width < 600.dp -> Compact
                width < 840.dp -> Medium
                else -> Expanded
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountTransactionsScreen(
    accountId: AccountId,
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    maintenanceService: DatabaseMaintenanceService,
    onAccountIdChange: (AccountId) -> Unit = {},
    onCurrencyIdChange: (CurrencyId?) -> Unit = {},
) {
    val allAccounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val allTransactions by transactionRepository.getAllTransactions()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val accountBalances by transactionRepository.getAccountBalances()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Selected account state - synced with parent's accountId parameter
    var selectedAccountId by remember { mutableStateOf(accountId) }

    // Sync selectedAccountId when parent's accountId parameter changes
    LaunchedEffect(accountId) {
        selectedAccountId = accountId
    }

    // Highlighted transaction state
    var highlightedTransactionId by remember { mutableStateOf<TransactionId?>(null) }

    // Edit transaction state
    var transactionToEdit by remember { mutableStateOf<Transfer?>(null) }

    // Coroutine scope for scroll animations
    val scrollScope = rememberCoroutineScope()

    // Get running balances for the selected account
    val runningBalances by transactionRepository.getRunningBalanceByAccount(selectedAccountId)
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Loading state - separate tracking for matrix (top) and transactions (bottom)
    var hasLoadedMatrix by remember { mutableStateOf(false) }
    var loadedAccountId by remember { mutableStateOf<AccountId?>(null) }

    // Track when matrix data (accounts, currencies, balances) has loaded
    LaunchedEffect(allAccounts, currencies, accountBalances) {
        if (allAccounts.isNotEmpty() || currencies.isNotEmpty() || accountBalances.isNotEmpty()) {
            hasLoadedMatrix = true
        }
    }

    // Track when transaction data has loaded for the current account
    LaunchedEffect(selectedAccountId, runningBalances) {
        loadedAccountId = selectedAccountId
    }

    val isMatrixLoading = !hasLoadedMatrix
    val isTransactionsLoading = loadedAccountId != selectedAccountId

    // Build a map of transactionId -> full Transaction for additional details
    val transactionMap = allTransactions.associateBy { it.id }

    // Get unique currency IDs from running balances for this account
    val accountCurrencyIds = runningBalances.map { it.transactionAmount.currency.id }.distinct()
    val accountCurrencies = currencies.filter { it.id in accountCurrencyIds }

    // Selected currency state - default to first currency if available
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }

    // Notify parent when selected currency changes
    LaunchedEffect(selectedCurrencyId) {
        onCurrencyIdChange(selectedCurrencyId)
    }

    // Update selected currency when account currencies change
    LaunchedEffect(accountCurrencies) {
        if (accountCurrencies.isNotEmpty()) {
            // If currently selected currency doesn't exist in the new account's currencies, clear it
            // This allows showing all currencies (null) or keeps a valid selected currency
            if (selectedCurrencyId != null && accountCurrencies.none { it.id == selectedCurrencyId }) {
                selectedCurrencyId = null
            }
        } else {
            selectedCurrencyId = null
        }
    }

    // Filter running balances by selected currency (or show all if no currency selected)
    val filteredRunningBalances =
        selectedCurrencyId?.let { currencyId ->
            runningBalances.filter { it.transactionAmount.currency.id == currencyId }
        } ?: runningBalances

    // Get all unique currencies from account balances for matrix
    val uniqueCurrencyIds = accountBalances.map { it.balance.currency.id }.distinct()

    // Hoist scroll states to enable auto-scrolling from transaction clicks
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        val screenSizeClass = ScreenSizeClass.fromWidth(maxWidth)

        // Get density for dp to pixel conversion
        val density = androidx.compose.ui.platform.LocalDensity.current

        // Capture container dimensions for centering calculations
        val containerWidthDp = maxWidth
        val containerHeightDp = maxHeight

        Column(modifier = Modifier.fillMaxSize()) {
            // Account Matrix Section (30% of screen) - with independent loading
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.3f)
                        .padding(bottom = 8.dp),
            ) {
                if (isMatrixLoading) {
                    // Loading indicator for account matrix
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                } else if (allAccounts.isNotEmpty()) {
                    // Account Picker - Buttons with balances underneath in table format
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Top row: Empty corner + Account names (always visible, scrolls horizontally)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Empty corner space for currency label column
                            Spacer(modifier = Modifier.width(60.dp))

                            // Account names row (horizontally scrollable)
                            Row(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .horizontalScroll(horizontalScrollState),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                allAccounts.forEach { account ->
                                    val isSelectedColumn = selectedAccountId == account.id
                                    val isColumnSelected = isSelectedColumn && selectedCurrencyId == null
                                    val isCellSelected = isSelectedColumn && selectedCurrencyId != null
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(120.dp)
                                                .background(
                                                    when {
                                                        isColumnSelected -> MaterialTheme.colorScheme.primaryContainer
                                                        isCellSelected -> MaterialTheme.colorScheme.secondaryContainer
                                                        else -> MaterialTheme.colorScheme.surface
                                                    },
                                                )
                                                .clickable {
                                                    selectedAccountId = account.id
                                                    selectedCurrencyId = null // Clear currency to show all currencies
                                                }
                                                .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = account.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            color =
                                                when {
                                                    isColumnSelected -> MaterialTheme.colorScheme.primary
                                                    isCellSelected -> MaterialTheme.colorScheme.secondary
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Bottom area: Currency labels + Balance grid (vertically scrollable)
                        Row(modifier = Modifier.weight(1f)) {
                            // Left column: Currency labels (always visible, scrolls vertically in sync)
                            Column(
                                modifier =
                                    Modifier
                                        .width(60.dp)
                                        .verticalScroll(verticalScrollState),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                uniqueCurrencyIds.forEach { currencyId ->
                                    val currency = currencies.find { it.id == currencyId }
                                    val isCurrencySelected = selectedCurrencyId == currencyId
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(60.dp)
                                                .background(
                                                    if (isCurrencySelected) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surface
                                                    },
                                                )
                                                .padding(vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "${currency?.code ?: "?"}:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color =
                                                if (isCurrencySelected) {
                                                    MaterialTheme.colorScheme.secondary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                    }
                                }
                            }

                            // Right side: Balance grid (scrolls both vertically and horizontally)
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .verticalScroll(verticalScrollState)
                                        .horizontalScroll(horizontalScrollState),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Row for each currency
                                uniqueCurrencyIds.forEach { currencyId ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Balance for each account
                                        allAccounts.forEach { account ->
                                            val balance =
                                                accountBalances.find {
                                                    it.accountId == account.id && it.balance.currency.id == currencyId
                                                }
                                            val isSelectedCell = selectedAccountId == account.id && selectedCurrencyId == currencyId
                                            val isColumnSelected = selectedAccountId == account.id && selectedCurrencyId == null

                                            val backgroundColor =
                                                when {
                                                    isSelectedCell -> MaterialTheme.colorScheme.primaryContainer
                                                    isColumnSelected -> MaterialTheme.colorScheme.primaryContainer
                                                    else -> MaterialTheme.colorScheme.surface
                                                }

                                            Box(
                                                modifier =
                                                    Modifier
                                                        .width(120.dp)
                                                        .background(backgroundColor)
                                                        .clickable(enabled = balance != null) {
                                                            selectedAccountId = account.id
                                                            selectedCurrencyId = currencyId
                                                        }
                                                        .padding(vertical = 4.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (balance != null) {
                                                    Text(
                                                        text = formatAmount(balance.balance),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                            if (balance.balance.amount >= 0) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.error
                                                            },
                                                        maxLines = 1,
                                                    )
                                                } else {
                                                    // Empty cell if account doesn't have this currency
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
                    }
                }
            }

            // Transactions Section (70% of screen) - with independent loading
            if (isTransactionsLoading) {
                // Loading indicator for transactions
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            } else if (runningBalances.isEmpty()) {
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
            } else if (filteredRunningBalances.isEmpty() && selectedCurrencyId != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No transactions for selected currency.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Column Headers - use fixed font size to ensure consistency
                val headerStyle = MaterialTheme.typography.labelSmall
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Date",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(0.15f),
                    )
                    if (screenSizeClass != ScreenSizeClass.Compact) {
                        Text(
                            text = "Time",
                            style = headerStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.1f),
                        )
                    }
                    Text(
                        text = "Account",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(0.2f).padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "Description",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(0.25f).padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "Amount",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(0.15f),
                    )
                    Text(
                        text = "Balance",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(0.15f).padding(start = 8.dp),
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = filteredRunningBalances,
                        key = { "${it.transactionId}-${it.accountId}" },
                    ) { runningBalance ->
                        val transaction = transactionMap[runningBalance.transactionId]
                        AccountTransactionCard(
                            runningBalance = runningBalance,
                            transaction = transaction,
                            accounts = allAccounts,
                            screenSizeClass = screenSizeClass,
                            isHighlighted = highlightedTransactionId == runningBalance.transactionId,
                            onEditClick = { transfer ->
                                transactionToEdit = transfer
                            },
                            onAccountClick = { clickedAccountId ->
                                highlightedTransactionId = runningBalance.transactionId
                                selectedCurrencyId = runningBalance.transactionAmount.currency.id

                                // Notify parent to switch to the clicked account
                                onAccountIdChange(clickedAccountId)

                                // Auto-scroll matrix to the clicked account and transaction currency
                                scrollScope.launch {
                                    // Calculate horizontal scroll position for the account
                                    val accountIndex = allAccounts.indexOfFirst { it.id == accountId }
                                    if (accountIndex >= 0) {
                                        // Convert dp to pixels using density
                                        with(density) {
                                            // Each account column: 120.dp width + 8.dp spacing
                                            val columnWidthPx = 120.dp.toPx()
                                            val spacingPx = 8.dp.toPx()

                                            // Calculate viewport width (total width - currency label column - padding)
                                            val currencyLabelWidthPx = 60.dp.toPx()
                                            val viewportWidthPx = containerWidthDp.toPx() - currencyLabelWidthPx

                                            // Calculate column position
                                            val columnStartPx = accountIndex * (columnWidthPx + spacingPx)
                                            val columnCenterPx = columnStartPx + (columnWidthPx / 2)

                                            // Center the column in the viewport
                                            val targetScrollX = (columnCenterPx - (viewportWidthPx / 2)).coerceAtLeast(0f).toInt()

                                            // Calculate vertical scroll position for the currency
                                            val currencyIndex =
                                                uniqueCurrencyIds.indexOfFirst {
                                                    it == runningBalance.transactionAmount.currency.id
                                                }
                                            if (currencyIndex >= 0) {
                                                // Each currency row: text + padding + spacing ≈ 28.dp
                                                val rowHeightPx = 28.dp.toPx()

                                                // Calculate viewport height (30% of container height - account header row - spacing)
                                                val matrixHeightPx = containerHeightDp.toPx() * 0.3f
                                                val accountHeaderHeightPx = 24.dp.toPx() // Account name header row
                                                val viewportHeightPx = matrixHeightPx - accountHeaderHeightPx

                                                // Calculate row position
                                                val rowStartPx = currencyIndex * rowHeightPx
                                                val rowCenterPx = rowStartPx + (rowHeightPx / 2)

                                                // Center the row in the viewport
                                                val targetScrollY = (rowCenterPx - (viewportHeightPx / 2)).coerceAtLeast(0f).toInt()

                                                // Animate both scrolls concurrently
                                                launch { horizontalScrollState.animateScrollTo(targetScrollX) }
                                                launch { verticalScrollState.animateScrollTo(targetScrollY) }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Show edit dialog if a transaction is selected for editing
    transactionToEdit?.let { transfer ->
        TransactionEditDialog(
            transaction = transfer,
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            maintenanceService = maintenanceService,
            onDismiss = { transactionToEdit = null },
        )
    }
}

@Composable
fun AccountTransactionCard(
    runningBalance: AccountRow,
    transaction: Transfer?,
    accounts: List<Account>,
    screenSizeClass: ScreenSizeClass,
    isHighlighted: Boolean = false,
    onAccountClick: (AccountId) -> Unit = {},
    onEditClick: (Transfer) -> Unit = {},
) {
    // Determine which account to display based on the current view
    // The account column should show the OTHER account in the transaction
    // runningBalance.accountId tells us which account's perspective we're viewing
    val otherAccount =
        transaction?.let { txn ->
            when {
                // If current account is the source, show the target (where money went)
                txn.sourceAccountId == runningBalance.accountId -> accounts.find { it.id == txn.targetAccountId }
                // If current account is the target, show the source (where money came from)
                txn.targetAccountId == runningBalance.accountId -> accounts.find { it.id == txn.sourceAccountId }
                // Fallback: shouldn't happen, but if neither matches, show nothing
                else -> null
            }
        }

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
        // Use consistent style and autoSize for all columns
        val cellStyle = MaterialTheme.typography.bodyMedium
        val cellAutoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp)

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Date column
            val dateTime = runningBalance.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = "${dateTime.date}",
                style = cellStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f),
            )

            // Time column (only on medium/expanded screens)
            if (screenSizeClass != ScreenSizeClass.Compact) {
                Text(
                    text = "${dateTime.time}",
                    style = cellStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    autoSize = cellAutoSize,
                    modifier = Modifier.weight(0.1f),
                )
            }

            // Account name column (clickable)
            Text(
                text = otherAccount?.name ?: "Unknown",
                style = cellStyle,
                color =
                    if (otherAccount != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier =
                    Modifier
                        .weight(0.2f)
                        .padding(horizontal = 8.dp)
                        .clickable(enabled = otherAccount != null) {
                            otherAccount?.id?.let { accountId ->
                                onAccountClick(accountId)
                            }
                        },
            )

            // Description column
            transaction?.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = cellStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        autoSize = cellAutoSize,
                        modifier = Modifier.weight(0.25f).padding(horizontal = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(0.25f))
                }
            } ?: Spacer(modifier = Modifier.weight(0.25f))

            // Amount column
            Text(
                text = formatAmount(runningBalance.transactionAmount),
                style = cellStyle,
                color =
                    if (runningBalance.transactionAmount.amount >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = TextAlign.End,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f),
            )

            // Balance column
            Text(
                text = formatAmount(runningBalance.runningBalance),
                style = cellStyle,
                color =
                    if (runningBalance.runningBalance.amount >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = TextAlign.End,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f).padding(start = 8.dp),
            )

            // Edit button
            if (transaction != null) {
                IconButton(
                    onClick = { onEditClick(transaction) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Text(
                        text = "\u270F\uFE0F",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntryDialog(
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    maintenanceService: DatabaseMaintenanceService,
    preSelectedSourceAccountId: AccountId? = null,
    preSelectedCurrencyId: CurrencyId? = null,
    onDismiss: () -> Unit,
) {
    // Collect accounts and currencies from Flows so newly created items appear immediately
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var sourceAccountId by remember { mutableStateOf(preSelectedSourceAccountId) }
    var targetAccountId by remember { mutableStateOf<AccountId?>(null) }
    var currencyId by remember { mutableStateOf<CurrencyId?>(preSelectedCurrencyId) }
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
    var showCreateCurrencyDialog by remember { mutableStateOf(false) }
    var creatingForSource by remember { mutableStateOf(true) }

    var sourceAccountExpanded by remember { mutableStateOf(false) }
    var targetAccountExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Transaction") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Source Account Dropdown with search
                var sourceAccountSearchQuery by remember { mutableStateOf("") }
                val filteredSourceAccounts =
                    remember(accounts, sourceAccountSearchQuery, targetAccountId) {
                        val available = accounts.filter { it.id != targetAccountId }
                        if (sourceAccountSearchQuery.isBlank()) {
                            available
                        } else {
                            available.filter { account ->
                                account.name.contains(sourceAccountSearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = sourceAccountExpanded,
                    onExpandedChange = { sourceAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (sourceAccountExpanded) {
                                sourceAccountSearchQuery
                            } else {
                                accounts.find { it.id == sourceAccountId }?.name ?: ""
                            },
                        onValueChange = { sourceAccountSearchQuery = it },
                        label = { Text("From Account") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = sourceAccountExpanded,
                        onDismissRequest = {
                            sourceAccountExpanded = false
                            sourceAccountSearchQuery = ""
                        },
                    ) {
                        filteredSourceAccounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    sourceAccountId = account.id
                                    sourceAccountExpanded = false
                                    sourceAccountSearchQuery = ""
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = true
                                showCreateAccountDialog = true
                                sourceAccountExpanded = false
                                sourceAccountSearchQuery = ""
                            },
                        )
                    }
                }

                // Target Account Dropdown with search
                var targetAccountSearchQuery by remember { mutableStateOf("") }
                val filteredTargetAccounts =
                    remember(accounts, targetAccountSearchQuery, sourceAccountId) {
                        val available = accounts.filter { it.id != sourceAccountId }
                        if (targetAccountSearchQuery.isBlank()) {
                            available
                        } else {
                            available.filter { account ->
                                account.name.contains(targetAccountSearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = targetAccountExpanded,
                    onExpandedChange = { targetAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (targetAccountExpanded) {
                                targetAccountSearchQuery
                            } else {
                                accounts.find { it.id == targetAccountId }?.name ?: ""
                            },
                        onValueChange = { targetAccountSearchQuery = it },
                        label = { Text("To Account") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = targetAccountExpanded,
                        onDismissRequest = {
                            targetAccountExpanded = false
                            targetAccountSearchQuery = ""
                        },
                    ) {
                        filteredTargetAccounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    targetAccountId = account.id
                                    targetAccountExpanded = false
                                    targetAccountSearchQuery = ""
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = false
                                showCreateAccountDialog = true
                                targetAccountExpanded = false
                                targetAccountSearchQuery = ""
                            },
                        )
                    }
                }

                // Currency Dropdown with search
                var currencySearchQuery by remember { mutableStateOf("") }
                val filteredCurrencies =
                    remember(currencies, currencySearchQuery) {
                        if (currencySearchQuery.isBlank()) {
                            currencies
                        } else {
                            currencies.filter { currency ->
                                currency.code.contains(currencySearchQuery, ignoreCase = true) ||
                                    currency.name.contains(currencySearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = currencyExpanded,
                    onExpandedChange = { currencyExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (currencyExpanded) {
                                currencySearchQuery
                            } else {
                                currencies.find { it.id == currencyId }?.let { "${it.code} - ${it.name}" } ?: ""
                            },
                        onValueChange = { currencySearchQuery = it },
                        label = { Text("Currency") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = currencyExpanded,
                        onDismissRequest = {
                            currencyExpanded = false
                            currencySearchQuery = ""
                        },
                    ) {
                        filteredCurrencies.forEach { currency ->
                            DropdownMenuItem(
                                text = { Text("${currency.code} - ${currency.name}") },
                                onClick = {
                                    currencyId = currency.id
                                    currencyExpanded = false
                                    currencySearchQuery = ""
                                },
                            )
                        }
                        // Always show "Create New Currency" option
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Currency") },
                            onClick = {
                                showCreateCurrencyDialog = true
                                currencyExpanded = false
                                currencySearchQuery = ""
                            },
                        )
                    }
                }

                // Date and Time Pickers
                val dateTimeTextStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Date Picker - ISO format (YYYY-MM-DD)
                    OutlinedTextField(
                        value = selectedDate.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        textStyle = dateTimeTextStyle,
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
                        modifier = Modifier.weight(1.5f),
                        enabled = false,
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )

                    // Time Picker - ISO format (HH:MM:SS)
                    val time = LocalTime(selectedHour, selectedMinute)
                    OutlinedTextField(
                        value = time.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        textStyle = dateTimeTextStyle,
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
                        singleLine = true,
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
                        currencyId == null -> errorMessage = "Please select a currency"
                        amount.isBlank() -> errorMessage = "Amount is required"
                        amount.toDoubleOrNull() == null -> errorMessage = "Invalid amount"
                        amount.toDouble() <= 0 -> errorMessage = "Amount must be greater than 0"
                        description.isBlank() -> errorMessage = "Description is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    // Get the currency object
                                    val currency =
                                        currencies.find { it.id == currencyId }
                                            ?: throw IllegalStateException("Currency not found")

                                    // Convert selected date and time to Instant
                                    val timestamp =
                                        selectedDate
                                            .atTime(selectedHour, selectedMinute, 0)
                                            .toInstant(TimeZone.currentSystemDefault())
                                    val transfer =
                                        Transfer(
                                            id = TransferId(Uuid.random()),
                                            timestamp = timestamp,
                                            description = description.trim(),
                                            sourceAccountId = sourceAccountId!!,
                                            targetAccountId = targetAccountId!!,
                                            amount = Money.fromDisplayValue(amount.toDouble(), currency),
                                        )
                                    transactionRepository.createTransfer(transfer)

                                    maintenanceService.refreshMaterializedViews()

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
            categoryRepository = categoryRepository,
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

    // Currency Creation Dialog
    if (showCreateCurrencyDialog) {
        CreateCurrencyDialogInline(
            currencyRepository = currencyRepository,
            onCurrencyCreated = { newCurrencyId ->
                currencyId = newCurrencyId
                showCreateCurrencyDialog = false
            },
            onDismiss = { showCreateCurrencyDialog = false },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditDialog(
    transaction: Transfer,
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    maintenanceService: DatabaseMaintenanceService,
    onDismiss: () -> Unit,
) {
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var sourceAccountId by remember { mutableStateOf(transaction.sourceAccountId) }
    var targetAccountId by remember { mutableStateOf(transaction.targetAccountId) }
    var currencyId by remember { mutableStateOf(transaction.amount.currency.id) }
    var amount by remember { mutableStateOf(transaction.amount.toDisplayValue().toString()) }
    var description by remember { mutableStateOf(transaction.description) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val transactionDateTime = transaction.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    var selectedDate by remember { mutableStateOf(transactionDateTime.date) }
    var selectedHour by remember { mutableStateOf(transactionDateTime.hour) }
    var selectedMinute by remember { mutableStateOf(transactionDateTime.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var showCreateCurrencyDialog by remember { mutableStateOf(false) }
    var creatingForSource by remember { mutableStateOf(true) }

    var sourceAccountExpanded by remember { mutableStateOf(false) }
    var targetAccountExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Transaction") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Source Account Dropdown with search
                var sourceAccountSearchQuery by remember { mutableStateOf("") }
                val filteredSourceAccounts =
                    remember(accounts, sourceAccountSearchQuery, targetAccountId) {
                        val available = accounts.filter { it.id != targetAccountId }
                        if (sourceAccountSearchQuery.isBlank()) {
                            available
                        } else {
                            available.filter { account ->
                                account.name.contains(sourceAccountSearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = sourceAccountExpanded,
                    onExpandedChange = { sourceAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (sourceAccountExpanded) {
                                sourceAccountSearchQuery
                            } else {
                                accounts.find { it.id == sourceAccountId }?.name ?: ""
                            },
                        onValueChange = { sourceAccountSearchQuery = it },
                        label = { Text("From Account") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = sourceAccountExpanded,
                        onDismissRequest = {
                            sourceAccountExpanded = false
                            sourceAccountSearchQuery = ""
                        },
                    ) {
                        filteredSourceAccounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    sourceAccountId = account.id
                                    sourceAccountExpanded = false
                                    sourceAccountSearchQuery = ""
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = true
                                showCreateAccountDialog = true
                                sourceAccountExpanded = false
                                sourceAccountSearchQuery = ""
                            },
                        )
                    }
                }

                // Target Account Dropdown with search
                var targetAccountSearchQuery by remember { mutableStateOf("") }
                val filteredTargetAccounts =
                    remember(accounts, targetAccountSearchQuery, sourceAccountId) {
                        val available = accounts.filter { it.id != sourceAccountId }
                        if (targetAccountSearchQuery.isBlank()) {
                            available
                        } else {
                            available.filter { account ->
                                account.name.contains(targetAccountSearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = targetAccountExpanded,
                    onExpandedChange = { targetAccountExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (targetAccountExpanded) {
                                targetAccountSearchQuery
                            } else {
                                accounts.find { it.id == targetAccountId }?.name ?: ""
                            },
                        onValueChange = { targetAccountSearchQuery = it },
                        label = { Text("To Account") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetAccountExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = targetAccountExpanded,
                        onDismissRequest = {
                            targetAccountExpanded = false
                            targetAccountSearchQuery = ""
                        },
                    ) {
                        filteredTargetAccounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    targetAccountId = account.id
                                    targetAccountExpanded = false
                                    targetAccountSearchQuery = ""
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Account") },
                            onClick = {
                                creatingForSource = false
                                showCreateAccountDialog = true
                                targetAccountExpanded = false
                                targetAccountSearchQuery = ""
                            },
                        )
                    }
                }

                // Currency Dropdown with search
                var currencySearchQuery by remember { mutableStateOf("") }
                val filteredCurrencies =
                    remember(currencies, currencySearchQuery) {
                        if (currencySearchQuery.isBlank()) {
                            currencies
                        } else {
                            currencies.filter { currency ->
                                currency.code.contains(currencySearchQuery, ignoreCase = true) ||
                                    currency.name.contains(currencySearchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = currencyExpanded,
                    onExpandedChange = { currencyExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            if (currencyExpanded) {
                                currencySearchQuery
                            } else {
                                currencies.find { it.id == currencyId }?.let { "${it.code} - ${it.name}" } ?: ""
                            },
                        onValueChange = { currencySearchQuery = it },
                        label = { Text("Currency") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = currencyExpanded,
                        onDismissRequest = {
                            currencyExpanded = false
                            currencySearchQuery = ""
                        },
                    ) {
                        filteredCurrencies.forEach { currency ->
                            DropdownMenuItem(
                                text = { Text("${currency.code} - ${currency.name}") },
                                onClick = {
                                    currencyId = currency.id
                                    currencyExpanded = false
                                    currencySearchQuery = ""
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Currency") },
                            onClick = {
                                showCreateCurrencyDialog = true
                                currencyExpanded = false
                                currencySearchQuery = ""
                            },
                        )
                    }
                }

                // Date and Time Pickers
                val dateTimeTextStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = selectedDate.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        textStyle = dateTimeTextStyle,
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
                        modifier = Modifier.weight(1.5f),
                        enabled = false,
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )

                    val time = LocalTime(selectedHour, selectedMinute)
                    OutlinedTextField(
                        value = time.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        textStyle = dateTimeTextStyle,
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
                        singleLine = true,
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
                        sourceAccountId == targetAccountId -> errorMessage = "Source and target accounts must be different"
                        amount.isBlank() -> errorMessage = "Amount is required"
                        amount.toDoubleOrNull() == null -> errorMessage = "Invalid amount"
                        amount.toDouble() <= 0 -> errorMessage = "Amount must be greater than 0"
                        description.isBlank() -> errorMessage = "Description is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val currency =
                                        currencies.find { it.id == currencyId }
                                            ?: throw IllegalStateException("Currency not found")

                                    val timestamp =
                                        selectedDate
                                            .atTime(selectedHour, selectedMinute, 0)
                                            .toInstant(TimeZone.currentSystemDefault())
                                    val updatedTransfer =
                                        Transfer(
                                            id = transaction.id,
                                            timestamp = timestamp,
                                            description = description.trim(),
                                            sourceAccountId = sourceAccountId,
                                            targetAccountId = targetAccountId,
                                            amount = Money.fromDisplayValue(amount.toDouble(), currency),
                                        )
                                    transactionRepository.updateTransfer(updatedTransfer)

                                    maintenanceService.refreshMaterializedViews()

                                    onDismiss()
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to update transaction: ${e.message}" }
                                    errorMessage = "Failed to update transaction: ${e.message}"
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
                    Text("Update")
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

    if (showCreateAccountDialog) {
        CreateAccountDialogInline(
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
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

    if (showCreateCurrencyDialog) {
        CreateCurrencyDialogInline(
            currencyRepository = currencyRepository,
            onCurrencyCreated = { newCurrencyId ->
                currencyId = newCurrencyId
                showCreateCurrencyDialog = false
            },
            onDismiss = { showCreateCurrencyDialog = false },
        )
    }

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
    categoryRepository: CategoryRepository,
    onAccountCreated: (AccountId) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf(-1L) }
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
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

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && !isSaving },
                ) {
                    OutlinedTextField(
                        value =
                            selectedCategoryName
                                ?: categories.find { it.id == selectedCategoryId }?.name
                                ?: "Uncategorized",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        enabled = !isSaving,
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    selectedCategoryName = null
                                    expanded = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Category") },
                            onClick = {
                                expanded = false
                                showCreateCategoryDialog = true
                            },
                        )
                    }
                }

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
                                // Placeholder ID, repository will assign actual ID
                                val newAccount =
                                    Account(
                                        id = AccountId(0),
                                        name = name.trim(),
                                        openingDate = now,
                                        categoryId = selectedCategoryId,
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

    if (showCreateCategoryDialog) {
        CreateCategoryDialog(
            categoryRepository = categoryRepository,
            onCategoryCreated = { categoryId, categoryName ->
                selectedCategoryId = categoryId
                selectedCategoryName = categoryName
                showCreateCategoryDialog = false
            },
            onDismiss = { showCreateCategoryDialog = false },
        )
    }
}

@Composable
fun CreateCurrencyDialogInline(
    currencyRepository: CurrencyRepository,
    onCurrencyCreated: (CurrencyId) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Currency") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(3) },
                    label = { Text("Currency Code (e.g., USD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Currency Name (e.g., US Dollar)") },
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
                        code.isBlank() -> errorMessage = "Currency code is required"
                        code.length != 3 -> errorMessage = "Currency code must be 3 characters"
                        name.isBlank() -> errorMessage = "Currency name is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val currencyId = currencyRepository.upsertCurrencyByCode(code.trim(), name.trim())
                                    onCurrencyCreated(currencyId)
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to create currency: ${e.message}" }
                                    errorMessage = "Failed to create currency: ${e.message}"
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
}
