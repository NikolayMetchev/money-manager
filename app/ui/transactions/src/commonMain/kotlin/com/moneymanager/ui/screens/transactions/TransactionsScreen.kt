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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.compose.scrollbar.VerticalScrollbarForScrollState
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.ui.components.EditAccountDialog
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.linuxHorizontalScrollWheel
import com.moneymanager.ui.util.ScreenSizeClass
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun horizontalMatrixScrollTarget(
    accountIndex: Int,
    allAccounts: List<Account>,
    accountColumnWidths: Map<AccountId, Dp>,
    containerWidthDp: Dp,
    density: Density,
): Int =
    with(density) {
        val spacingPx = 8.dp.toPx()
        val currencyLabelWidthPx = 60.dp.toPx()
        val viewportWidthPx = containerWidthDp.toPx() - currencyLabelWidthPx
        var columnStartPx = 0f
        for (i in 0 until accountIndex) {
            val acc = allAccounts[i]
            val colWidth = accountColumnWidths[acc.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
            columnStartPx += colWidth.toPx() + spacingPx
        }
        val targetAccount = allAccounts[accountIndex]
        val targetColumnWidth = accountColumnWidths[targetAccount.id] ?: ACCOUNT_COLUMN_MIN_WIDTH
        val columnCenterPx = columnStartPx + (targetColumnWidth.toPx() / 2)
        (columnCenterPx - (viewportWidthPx / 2)).coerceAtLeast(0f).toInt()
    }

private fun verticalMatrixScrollTarget(
    currencyIndex: Int,
    containerHeightDp: Dp,
    density: Density,
): Int =
    with(density) {
        val rowHeightPx = 28.dp.toPx()
        val matrixHeightPx = containerHeightDp.toPx() * 0.3f
        val accountHeaderHeightPx = 24.dp.toPx()
        val viewportHeightPx = matrixHeightPx - accountHeaderHeightPx
        val rowStartPx = currencyIndex * rowHeightPx
        val rowCenterPx = rowStartPx + (rowHeightPx / 2)
        (rowCenterPx - (viewportHeightPx / 2)).coerceAtLeast(0f).toInt()
    }

@Composable
fun AccountTransactionsScreen(
    accountId: AccountId,
    transactionRepository: TransactionReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    attributeTypeRepository: AttributeTypeReadRepository,
    personRepository: PersonReadRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    maintenance: Maintenance,
    onAccountIdChange: (AccountId) -> Unit = {},
    onCurrencyIdChange: (CurrencyId?) -> Unit = {},
    onAccountClick: (AccountId, String, CurrencyId?, TransferId?) -> Unit = { _, _, _, _ -> },
    onAuditClick: (TransferId) -> Unit = {},
    onFeeLinkClick: (TransferId) -> Unit = {},
    scrollToTransferId: TransferId? = null,
    initialCurrencyId: CurrencyId? = null,
    externalRefreshTrigger: Int = 0,
) {
    val allAccounts by accountRepository
        .getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val accountBalances by transactionRepository
        .getAccountBalances()
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

    // Edit account state - stores the account selected for editing
    var accountToEdit by remember { mutableStateOf<Account?>(null) }

    // Refresh trigger - increment to force reload after edits
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch the actual transfer when transactionIdToEdit changes
    LaunchedEffect(transactionIdToEdit) {
        val id = transactionIdToEdit
        transactionToEdit =
            if (id != null) {
                transactionRepository.getTransactionById(id.id).first()
            } else {
                null
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

    // The currencies this account holds, from its balances — NOT from the loaded pages, which are
    // server-filtered to the selected currency (and empty while a reload is in flight).
    val accountCurrencyIds =
        accountBalances
            .filter { it.accountId == selectedAccountId }
            .map { it.balance.currency.id }
            .distinct()
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

    // Pages are already filtered to the selected currency by the repository queries.
    // Whether to show transactions marked as excluded
    var showExcluded by remember { mutableStateOf(false) }
    val displayedRunningBalances =
        if (showExcluded) runningBalances else runningBalances.filter { !it.isExcluded }

    // Get all unique currencies from account balances for matrix
    val uniqueCurrencyIds = accountBalances.map { CurrencyId(it.balance.currency.id.id) }.distinct()

    // Calculate column widths for each account based on account name and balance amounts
    val accountColumnWidths: Map<AccountId, Dp> =
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
        LaunchedEffect(selectedAccountId, selectedCurrencyId, pageSize, scrollToTransferId, refreshTrigger, externalRefreshTrigger) {
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
                            currencyId = selectedCurrencyId,
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
                            currencyId = selectedCurrencyId,
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
                        currencyId = selectedCurrencyId,
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
                        currencyId = selectedCurrencyId,
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
                horizontalScrollState.animateScrollTo(
                    horizontalMatrixScrollTarget(
                        accountIndex = accountIndex,
                        allAccounts = allAccounts,
                        accountColumnWidths = accountColumnWidths,
                        containerWidthDp = containerWidthDp,
                        density = density,
                    ),
                )
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
                                        .linuxHorizontalScrollWheel(horizontalScrollState)
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
                                                ).clickable {
                                                    selectedAccountId = account.id
                                                    selectedCurrencyId = null // Clear currency to show all currencies
                                                    onAccountClick(account.id, account.name, null, null)
                                                }.padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
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
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            IconButton(
                                                onClick = { accountToEdit = account },
                                                modifier = Modifier.size(20.dp),
                                            ) {
                                                Text(
                                                    text = "⚙️",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
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
                                                    ).padding(vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "${currencies.find { it.id == currencyId }?.code ?: "?"}:",
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
                                            .linuxHorizontalScrollWheel(horizontalScrollState)
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
                                                                onAccountClick(account.id, account.name, currencyId, null)
                                                            }.padding(vertical = 4.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    if (balance != null) {
                                                        Text(
                                                            text = formatAmount(balance.balance),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color =
                                                                if (!balance.balance.isNegative()) {
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

            // Transactions toolbar — filter options that apply to the list below
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = showExcluded,
                    onCheckedChange = { showExcluded = it },
                )
                Text(
                    text = "Show excluded",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { showExcluded = !showExcluded },
                )
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
                        text =
                            if (selectedCurrencyId != null) {
                                "No transactions for selected currency."
                            } else {
                                "No transactions yet for this account."
                            },
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
                        text = "Rec.",
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(40.dp),
                    )
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
                        selectedCurrencyId = CurrencyId(transaction.transactionAmount.currency.id.id)

                        // Now find the index in the filtered list (which now includes this currency)
                        val index =
                            runningBalances
                                .filter {
                                    it.transactionAmount.currency.id == transaction.transactionAmount.currency.id
                                }.indexOfFirst { it.transactionId.id == targetTransferId.id }

                        if (index >= 0) {
                            // Centre the target in the viewport instead of pinning it to the very top.
                            // animateScrollToItem places the item's top at viewport top + scrollOffset, so a
                            // negative offset of (half viewport - half row) drops it to the middle. Wait for
                            // the list to be measured first, otherwise viewportSize is still 0.
                            val viewportHeight =
                                snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
                            val rowHeight =
                                listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull()
                                    ?.size ?: 0
                            val centeringOffset = -(viewportHeight / 2 - rowHeight / 2)
                            listState.animateScrollToItem(index, centeringOffset)

                            // Scroll matrix to show the account and currency
                            val accountIndex = allAccounts.indexOfFirst { it.id == accountId }
                            val currencyIndex =
                                uniqueCurrencyIds.indexOfFirst {
                                    it == transaction.transactionAmount.currency.id
                                }

                            if (accountIndex >= 0 && currencyIndex >= 0) {
                                val targetScrollX =
                                    horizontalMatrixScrollTarget(
                                        accountIndex = accountIndex,
                                        allAccounts = allAccounts,
                                        accountColumnWidths = accountColumnWidths,
                                        containerWidthDp = containerWidthDp,
                                        density = density,
                                    )
                                val targetScrollY =
                                    verticalMatrixScrollTarget(
                                        currencyIndex = currencyIndex,
                                        containerHeightDp = containerHeightDp,
                                        density = density,
                                    )
                                launch { horizontalScrollState.animateScrollTo(targetScrollX) }
                                launch { verticalScrollState.animateScrollTo(targetScrollY) }
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
                            lastVisibleIndex >= displayedRunningBalances.size - 10 &&
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
                            items = displayedRunningBalances,
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
                                onFeeLinkClick = { linkedTransferId ->
                                    val targetIndex =
                                        displayedRunningBalances.indexOfFirst { it.transactionId.id == linkedTransferId.id }
                                    if (targetIndex < 0) {
                                        // The linked transfer isn't in the current list (different account/currency,
                                        // or not yet loaded): navigate, which loads its page and scrolls to it.
                                        onFeeLinkClick(linkedTransferId)
                                    } else {
                                        // It's in this list: just move the highlight. Only scroll when it's
                                        // off-screen — if it's already visible, leave the scroll position alone.
                                        highlightedTransactionId = linkedTransferId
                                        val alreadyVisible =
                                            listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                                        if (!alreadyVisible) {
                                            scrollScope.launch {
                                                val viewportHeight = listState.layoutInfo.viewportSize.height
                                                val rowHeight =
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull()
                                                        ?.size ?: 0
                                                listState.animateScrollToItem(targetIndex, -(viewportHeight / 2 - rowHeight / 2))
                                            }
                                        }
                                    }
                                },
                                onAccountClick = { clickedAccountId ->
                                    highlightedTransactionId = runningBalance.transactionId
                                    selectedCurrencyId = CurrencyId(runningBalance.transactionAmount.currency.id.id)

                                    // Notify parent to switch to the clicked account
                                    onAccountIdChange(clickedAccountId)

                                    // Auto-scroll matrix to the clicked account and transaction currency
                                    scrollScope.launch {
                                        // Calculate horizontal scroll position for the account
                                        val accountIndex = allAccounts.indexOfFirst { it.id == clickedAccountId }
                                        if (accountIndex >= 0) {
                                            val targetScrollX =
                                                horizontalMatrixScrollTarget(
                                                    accountIndex = accountIndex,
                                                    allAccounts = allAccounts,
                                                    accountColumnWidths = accountColumnWidths,
                                                    containerWidthDp = containerWidthDp,
                                                    density = density,
                                                )
                                            // Calculate vertical scroll position for the currency
                                            val currencyIndex =
                                                uniqueCurrencyIds.indexOfFirst {
                                                    it == runningBalance.transactionAmount.currency.id
                                                }
                                            if (currencyIndex >= 0) {
                                                // Each currency row: text + padding + spacing ≈ 28.dp
                                                val targetScrollY =
                                                    verticalMatrixScrollTarget(
                                                        currencyIndex = currencyIndex,
                                                        containerHeightDp = containerHeightDp,
                                                        density = density,
                                                    )

                                                // Animate both scrolls concurrently
                                                launch { horizontalScrollState.animateScrollTo(targetScrollX) }
                                                launch { verticalScrollState.animateScrollTo(targetScrollY) }
                                            }
                                        }
                                    }

                                    // Navigate to the clicked account (adds to navigation history),
                                    // scrolling to this same transfer as seen from the other account's side.
                                    val clickedAccount = allAccounts.find { it.id == clickedAccountId }
                                    if (clickedAccount != null) {
                                        onAccountClick(
                                            clickedAccountId,
                                            clickedAccount.name,
                                            CurrencyId(runningBalance.transactionAmount.currency.id.id),
                                            TransferId(runningBalance.transactionId.id),
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
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            personRepository = personRepository,
            maintenance = maintenance,
            onDismiss = { transactionIdToEdit = null },
            onSaved = { refreshTrigger++ },
        )
    }

    // Show edit dialog if an account is selected for editing
    accountToEdit?.let { account ->
        EditAccountDialog(
            account = account,
            accountAttributeRepository = accountAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            // No onSaved refresh needed: allAccounts is collected from a Flow and updates automatically
            onDismiss = { accountToEdit = null },
        )
    }
}
