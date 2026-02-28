@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.transactions

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.compose.scrollbar.VerticalScrollbarForScrollState
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AccountTransactionsScreen(
    accountId: AccountId,
    transactionRepository: TransactionRepository,
    transferSourceRepository: TransferSourceRepository,
    transferSourceQueries: TransferSourceQueries,
    entitySourceQueries: EntitySourceQueries,
    deviceRepository: DeviceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    maintenanceService: DatabaseMaintenanceService,
    deviceId: DeviceId,
    onAccountIdChange: (AccountId) -> Unit = {},
    onCurrencyIdChange: (CurrencyId?) -> Unit = {},
    onAccountClick: (AccountId, String, CurrencyId?) -> Unit = { _, _, _ -> },
    onAuditClick: (TransferId) -> Unit = {},
    scrollToTransferId: TransferId? = null,
    initialCurrencyId: CurrencyId? = null,
    externalRefreshTrigger: Int = 0,
) {
    val allAccounts by accountRepository.getAllAccounts()
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

    // Edit transaction state - stores the transfer ID to edit, actual transfer is fetched from repository
    var transactionIdToEdit by remember { mutableStateOf<TransferId?>(null) }
    var transactionToEdit by remember { mutableStateOf<Transfer?>(null) }

    // Refresh trigger - increment to force reload after edits
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch the actual transfer when transactionIdToEdit changes
    LaunchedEffect(transactionIdToEdit) {
        val id = transactionIdToEdit
        if (id != null) {
            transactionToEdit = transactionRepository.getTransactionById(id.id).first()
        } else {
            transactionToEdit = null
        }
    }

    // Coroutine scope for scroll animations and pagination
    val scrollScope = rememberSchemaAwareCoroutineScope()

    // Pagination state
    var runningBalances by remember { mutableStateOf<List<com.moneymanager.domain.model.AccountRow>>(emptyList()) }
    var currentPagingInfo by remember { mutableStateOf<com.moneymanager.domain.model.PagingInfo?>(null) }
    var isLoadingPage by remember { mutableStateOf(false) }
    var hasLoadedFirstPage by remember { mutableStateOf(false) }
    // Backward paging state - tracks if there are more items at the start of the list
    var hasPreviousPage by remember { mutableStateOf(false) }
    var isLoadingPreviousPage by remember { mutableStateOf(false) }

    // Loading state - separate tracking for matrix (top) and transactions (bottom)
    var hasLoadedMatrix by remember { mutableStateOf(false) }

    // Track when matrix data (accounts, currencies, balances) has loaded
    LaunchedEffect(allAccounts, currencies, accountBalances) {
        if (allAccounts.isNotEmpty() || currencies.isNotEmpty() || accountBalances.isNotEmpty()) {
            hasLoadedMatrix = true
        }
    }

    val isMatrixLoading = !hasLoadedMatrix
    val isTransactionsLoading = !hasLoadedFirstPage

    // Get unique currency IDs from running balances for this account
    val accountCurrencyIds = runningBalances.map { it.transactionAmount.currency.id }.distinct()
    val accountCurrencies = currencies.filter { it.id in accountCurrencyIds }

    // Selected currency state - initialized from parameter
    var selectedCurrencyId by remember { mutableStateOf(initialCurrencyId) }

    // Sync selectedCurrencyId when parent's initialCurrencyId parameter changes
    LaunchedEffect(initialCurrencyId) {
        selectedCurrencyId = initialCurrencyId
    }

    // Notify parent when selected currency changes
    LaunchedEffect(selectedCurrencyId) {
        onCurrencyIdChange(selectedCurrencyId)
    }

    // Update selected currency when account currencies change (only clear if invalid)
    LaunchedEffect(accountCurrencies) {
        if (accountCurrencies.isNotEmpty()) {
            // If currently selected currency doesn't exist in the new account's currencies, clear it
            // This allows showing all currencies (null) or keeps a valid selected currency
            if (selectedCurrencyId != null && accountCurrencies.none { it.id == selectedCurrencyId }) {
                selectedCurrencyId = null
            }
        } else if (initialCurrencyId == null) {
            // Only clear if we didn't receive an initial currency from navigation
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

    // Calculate column widths for each account based on account name and balance amounts
    val accountColumnWidths: Map<AccountId, androidx.compose.ui.unit.Dp> =
        remember(allAccounts, accountBalances) {
            allAccounts.associate { account ->
                // Calculate width needed for account name header
                val nameWidth = (account.name.length * 8 + 16).dp

                // Calculate max balance width for this account
                val maxBalanceWidth =
                    accountBalances
                        .filter { it.accountId == account.id }
                        .maxOfOrNull { formatAmount(it.balance).length }
                        ?.let { (it * 8 + 16).dp }
                        ?: 0.dp

                account.id to maxOf(nameWidth, maxBalanceWidth, ACCOUNT_COLUMN_MIN_WIDTH)
            }
        }

    // Hoist scroll states to enable auto-scrolling from transaction clicks
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Calculate page size based on screen height
        val pageSize =
            remember(maxHeight) {
                val itemHeightDp = 60.dp
                val visibleItems = (maxHeight / itemHeightDp).toInt()
                (visibleItems * 1.5).toInt().coerceAtLeast(20)
            }

        // Load first page when account changes or after edits (via refreshTrigger or externalRefreshTrigger)
        // If scrollToTransferId is provided, load the page containing that transaction
        LaunchedEffect(selectedAccountId, pageSize, scrollToTransferId, refreshTrigger, externalRefreshTrigger) {
            runningBalances = emptyList()
            currentPagingInfo = null
            hasLoadedFirstPage = false
            isLoadingPage = true
            hasPreviousPage = false

            try {
                if (scrollToTransferId != null) {
                    // Load the page containing the target transaction
                    val pageResult =
                        transactionRepository.getPageContainingTransaction(
                            accountId = selectedAccountId,
                            transactionId = scrollToTransferId,
                            pageSize = pageSize,
                        )
                    runningBalances = pageResult.items
                    currentPagingInfo = pageResult.pagingInfo
                    // Use the hasPrevious flag from the page result
                    hasPreviousPage = pageResult.hasPrevious
                } else {
                    // Normal first page load - no previous items
                    val result =
                        transactionRepository.getRunningBalanceByAccountPaginated(
                            accountId = selectedAccountId,
                            pageSize = pageSize,
                            pagingInfo = null,
                        )
                    runningBalances = result.items
                    currentPagingInfo = result.pagingInfo
                    hasPreviousPage = false
                }
                hasLoadedFirstPage = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is expected when user navigates away quickly
                throw e
            } catch (expected: Exception) {
                logger.error(expected) { "Failed to load transactions: ${expected.message}" }
            } finally {
                isLoadingPage = false
            }
        }

        // Function to load next page
        suspend fun loadNextPage() {
            if (isLoadingPage || currentPagingInfo?.hasMore != true) return

            isLoadingPage = true
            try {
                val result =
                    transactionRepository.getRunningBalanceByAccountPaginated(
                        accountId = selectedAccountId,
                        pageSize = pageSize,
                        pagingInfo = currentPagingInfo,
                    )
                runningBalances = runningBalances + result.items
                currentPagingInfo = result.pagingInfo
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is expected when user navigates away or scrolls fast
                // Rethrow to let coroutine machinery handle it properly
                throw e
            } catch (expected: Exception) {
                logger.error(expected) { "Failed to load more transactions: ${expected.message}" }
            } finally {
                isLoadingPage = false
            }
        }

        // Function to load previous page (backward pagination)
        suspend fun loadPreviousPage() {
            if (isLoadingPreviousPage || !hasPreviousPage || runningBalances.isEmpty()) return

            val firstItem = runningBalances.first()
            isLoadingPreviousPage = true
            try {
                val result =
                    transactionRepository.getRunningBalanceByAccountPaginatedBackward(
                        accountId = selectedAccountId,
                        pageSize = pageSize,
                        firstTimestamp = firstItem.timestamp,
                        firstId = firstItem.transactionId,
                    )
                if (result.items.isNotEmpty()) {
                    runningBalances = result.items + runningBalances
                }
                hasPreviousPage = result.pagingInfo.hasMore
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (expected: Exception) {
                logger.error(expected) { "Failed to load previous transactions: ${expected.message}" }
            } finally {
                isLoadingPreviousPage = false
            }
        }

        val screenSizeClass = ScreenSizeClass.fromWidth(maxWidth)

        // Get density for dp to pixel conversion
        val density = androidx.compose.ui.platform.LocalDensity.current

        // Capture container dimensions for centering calculations
        val containerWidthDp = maxWidth
        val containerHeightDp = maxHeight

        // Auto-scroll matrix horizontally when account changes (including on initial load)
        LaunchedEffect(selectedAccountId, allAccounts) {
            // Wait for accounts to load before attempting to scroll
            if (allAccounts.isEmpty()) return@LaunchedEffect

            // Scroll horizontally to center the selected account column
            val accountIndex = allAccounts.indexOfFirst { it.id == selectedAccountId }
            if (accountIndex >= 0) {
                with(density) {
                    val spacingPx = 8.dp.toPx()
                    val currencyLabelWidthPx = 60.dp.toPx()
                    val viewportWidthPx = containerWidthDp.toPx() - currencyLabelWidthPx

                    // Calculate column position by summing preceding column widths
                    var columnStartPx = 0f
                    for (i in 0 until accountIndex) {
                        val acc = allAccounts[i]
                        val colWidth = accountColumnWidths[acc.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                        columnStartPx += colWidth.toPx() + spacingPx
                    }
                    val targetAccount = allAccounts[accountIndex]
                    val targetColumnWidth = accountColumnWidths[targetAccount.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                    val columnCenterPx = columnStartPx + (targetColumnWidth.toPx() / 2)

                    // Center the column in the viewport
                    val targetScrollX = (columnCenterPx - (viewportWidthPx / 2)).coerceAtLeast(0f).toInt()

                    // Animate horizontal scroll
                    horizontalScrollState.animateScrollTo(targetScrollX)
                }
            }
        }

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
                                    val columnWidth = accountColumnWidths[account.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(columnWidth)
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
                                                    onAccountClick(account.id, account.name, null)
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
                        Box(modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.fillMaxSize()) {
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
                                                val columnWidth = accountColumnWidths[account.id] ?: ACCOUNT_COLUMN_MIN_WIDTH

                                                val backgroundColor =
                                                    when {
                                                        isSelectedCell -> MaterialTheme.colorScheme.primaryContainer
                                                        isColumnSelected -> MaterialTheme.colorScheme.primaryContainer
                                                        else -> MaterialTheme.colorScheme.surface
                                                    }

                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .width(columnWidth)
                                                            .background(backgroundColor)
                                                            .clickable(enabled = balance != null) {
                                                                selectedAccountId = account.id
                                                                selectedCurrencyId = currencyId
                                                                onAccountClick(account.id, account.name, currencyId)
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
                            VerticalScrollbarForScrollState(
                                scrollState = verticalScrollState,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }

                        // Horizontal scrollbar for the balance matrix
                        HorizontalScrollbarForScrollState(
                            scrollState = horizontalScrollState,
                            modifier = Modifier.fillMaxWidth().padding(start = 60.dp),
                        )
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
                    // Spacer for edit and audit button columns
                    Spacer(modifier = Modifier.width(64.dp))
                }

                val listState = rememberLazyListState()

                // Scroll to specific transaction if requested
                LaunchedEffect(scrollToTransferId, runningBalances) {
                    scrollToTransferId?.let { targetTransferId ->
                        // Set highlighted transaction (TransferId implements TransactionId)
                        highlightedTransactionId = targetTransferId

                        // First find the transaction in unfiltered list to get its currency
                        val transaction =
                            runningBalances.find { it.transactionId.id == targetTransferId.id }
                                ?: return@let

                        // Set currency filter to match the transaction
                        selectedCurrencyId = transaction.transactionAmount.currency.id

                        // Now find the index in the filtered list (which now includes this currency)
                        val index =
                            runningBalances.filter {
                                it.transactionAmount.currency.id == transaction.transactionAmount.currency.id
                            }.indexOfFirst { it.transactionId.id == targetTransferId.id }

                        if (index >= 0) {
                            // Scroll to the transaction
                            listState.animateScrollToItem(index)

                            // Scroll matrix to show the account and currency
                            val accountIndex = allAccounts.indexOfFirst { it.id == accountId }
                            val currencyIndex =
                                uniqueCurrencyIds.indexOfFirst {
                                    it == transaction.transactionAmount.currency.id
                                }

                            if (accountIndex >= 0 && currencyIndex >= 0) {
                                with(density) {
                                    val spacingPx = 8.dp.toPx()

                                    // Calculate horizontal scroll position
                                    var columnStartPx = 0f
                                    for (i in 0 until accountIndex) {
                                        val acc = allAccounts[i]
                                        val colWidth = accountColumnWidths[acc.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                                        columnStartPx += colWidth.toPx() + spacingPx
                                    }
                                    val targetAccount = allAccounts[accountIndex]
                                    val targetColumnWidth = accountColumnWidths[targetAccount.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                                    val currencyLabelWidthPx = 60.dp.toPx()
                                    val viewportWidthPx = containerWidthDp.toPx() - currencyLabelWidthPx
                                    val columnCenterPx = columnStartPx + (targetColumnWidth.toPx() / 2)
                                    val targetScrollX = (columnCenterPx - (viewportWidthPx / 2)).coerceAtLeast(0f).toInt()

                                    // Calculate vertical scroll position
                                    val rowHeightPx = 28.dp.toPx()
                                    val matrixHeightPx = containerHeightDp.toPx() * 0.3f
                                    val accountHeaderHeightPx = 24.dp.toPx()
                                    val viewportHeightPx = matrixHeightPx - accountHeaderHeightPx
                                    val rowStartPx = currencyIndex * rowHeightPx
                                    val rowCenterPx = rowStartPx + (rowHeightPx / 2)
                                    val targetScrollY = (rowCenterPx - (viewportHeightPx / 2)).coerceAtLeast(0f).toInt()

                                    // Animate scrolls
                                    launch { horizontalScrollState.animateScrollTo(targetScrollX) }
                                    launch { verticalScrollState.animateScrollTo(targetScrollY) }
                                }
                            }
                        }
                    }
                }

                // Trigger pagination when user scrolls near the end
                LaunchedEffect(listState, isLoadingPage, currentPagingInfo?.hasMore) {
                    snapshotFlow {
                        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        lastVisibleItem?.index
                    }.collect { lastVisibleIndex ->
                        // Load more when within 10 items of the end
                        if (lastVisibleIndex != null &&
                            lastVisibleIndex >= filteredRunningBalances.size - 10 &&
                            !isLoadingPage &&
                            currentPagingInfo?.hasMore == true
                        ) {
                            loadNextPage()
                        }
                    }
                }

                // Trigger backward pagination when user scrolls near the beginning
                LaunchedEffect(listState, isLoadingPreviousPage, hasPreviousPage) {
                    snapshotFlow {
                        val firstVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                        firstVisibleItem?.index
                    }.collect { firstVisibleIndex ->
                        // Load previous when within 5 items of the start
                        if (firstVisibleIndex != null &&
                            firstVisibleIndex <= 5 &&
                            !isLoadingPreviousPage &&
                            hasPreviousPage
                        ) {
                            loadPreviousPage()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = filteredRunningBalances,
                            key = { "${it.transactionId}-${it.accountId}" },
                        ) { runningBalance ->
                            AccountTransactionCard(
                                runningBalance = runningBalance,
                                accounts = allAccounts,
                                screenSizeClass = screenSizeClass,
                                isHighlighted = highlightedTransactionId == runningBalance.transactionId,
                                onEditClick = { transfer ->
                                    transactionIdToEdit = transfer.id
                                },
                                onAuditClick = onAuditClick,
                                onAccountClick = { clickedAccountId ->
                                    highlightedTransactionId = runningBalance.transactionId
                                    selectedCurrencyId = runningBalance.transactionAmount.currency.id

                                    // Notify parent to switch to the clicked account
                                    onAccountIdChange(clickedAccountId)

                                    // Auto-scroll matrix to the clicked account and transaction currency
                                    scrollScope.launch {
                                        // Calculate horizontal scroll position for the account
                                        val accountIndex = allAccounts.indexOfFirst { it.id == clickedAccountId }
                                        if (accountIndex >= 0) {
                                            // Convert dp to pixels using density
                                            with(density) {
                                                val spacingPx = 8.dp.toPx()

                                                // Calculate viewport width (total width - currency label column - padding)
                                                val currencyLabelWidthPx = 60.dp.toPx()
                                                val viewportWidthPx = containerWidthDp.toPx() - currencyLabelWidthPx

                                                // Calculate column position by summing preceding column widths
                                                var columnStartPx = 0f
                                                for (i in 0 until accountIndex) {
                                                    val acc = allAccounts[i]
                                                    val colWidth = accountColumnWidths[acc.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                                                    columnStartPx += colWidth.toPx() + spacingPx
                                                }
                                                val targetAccount = allAccounts[accountIndex]
                                                val targetColumnWidth = accountColumnWidths[targetAccount.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
                                                val columnCenterPx = columnStartPx + (targetColumnWidth.toPx() / 2)

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

                                    // Navigate to the clicked account (adds to navigation history)
                                    val clickedAccount = allAccounts.find { it.id == clickedAccountId }
                                    if (clickedAccount != null) {
                                        onAccountClick(
                                            clickedAccountId,
                                            clickedAccount.name,
                                            runningBalance.transactionAmount.currency.id,
                                        )
                                    }
                                },
                            )
                        }

                        // Loading indicator at bottom
                        if (isLoadingPage && currentPagingInfo?.hasMore == true) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = listState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    // Show edit dialog if a transaction is selected for editing
    transactionToEdit?.let { transfer ->
        TransactionEditDialog(
            transaction = transfer,
            transactionRepository = transactionRepository,
            transferSourceRepository = transferSourceRepository,
            transferSourceQueries = transferSourceQueries,
            entitySourceQueries = entitySourceQueries,
            deviceRepository = deviceRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            maintenanceService = maintenanceService,
            deviceId = deviceId,
            onDismiss = { transactionIdToEdit = null },
            onSaved = { refreshTrigger++ },
        )
    }
}
