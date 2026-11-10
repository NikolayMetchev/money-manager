@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.DateRangeFilterFields
import com.moneymanager.ui.components.EditAccountDialog
import com.moneymanager.ui.foundation.LocalStartupProbe
import com.moneymanager.ui.foundation.StartupMilestone
import com.moneymanager.ui.util.ScreenSizeClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    holidayCalendarRepository: HolidayCalendarReadRepository? = null,
    exchangeOrderRepository: ExchangeOrderReadRepository,
    maintenance: Maintenance,
    // Null disables trade editing (the exclusion toggle); tests of transfer behaviour needn't supply it.
    tradeRepository: TradeReadRepository? = null,
    onAccountIdChange: (AccountId) -> Unit = {},
    onCurrencyIdChange: (CurrencyId?) -> Unit = {},
    onAccountClick: (AccountId, String, CurrencyId?, TransferId?) -> Unit = { _, _, _, _ -> },
    onAuditClick: (TransferId) -> Unit = {},
    onFeeLinkClick: (TransferId) -> Unit = {},
    onOrdersClick: (AccountId, String) -> Unit = { _, _ -> },
    onOrderLinkClick: (ExchangeOrderId) -> Unit = {},
    scrollToTransferId: TransferId? = null,
    initialCurrencyId: CurrencyId? = null,
    externalRefreshTrigger: Int = 0,
    onBack: () -> Unit = {},
) {
    val allAccounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }
    val accountBalances by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        transactionRepository.getAccountBalances()
    }

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
    var tradeIdToEdit by remember { mutableStateOf<TradeId?>(null) }

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
    var firstPageFailed by remember { mutableStateOf(false) }
    // Backward paging state - tracks if there are more items at the start of the list
    var hasPreviousPage by remember { mutableStateOf(false) }
    var isLoadingPreviousPage by remember { mutableStateOf(false) }
    // Bumped after every completed first-page (re)load. The scroll-to-target effect keys on this
    // rather than on runningBalances: list contents can be equal across a reload (so an
    // equals-based key would not re-fire), while pagination prepends/appends must NOT re-fire it.
    var pageLoadGeneration by remember { mutableStateOf(0) }

    val startupProbe = LocalStartupProbe.current
    if (startupProbe != null) {
        LaunchedEffect(hasLoadedFirstPage) {
            if (hasLoadedFirstPage) startupProbe.reached(StartupMilestone.TRANSACTIONS_READY)
        }
    }

    val isTransactionsLoading = !hasLoadedFirstPage

    val accountAssets =
        remember(accountBalances, selectedAccountId) {
            accountBalances
                .filter { it.accountId == selectedAccountId }
                .map { it.balance.asset }
                .distinctBy { it.id.id }
                .sortedBy { it.code }
        }

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

    LaunchedEffect(accountAssets) {
        if (accountAssets.isNotEmpty()) {
            if (selectedCurrencyId != null && accountAssets.none { it.id == selectedCurrencyId }) {
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
    var dateRange by remember { mutableStateOf(TransactionDateRange()) }
    var showDates by rememberSaveable { mutableStateOf(false) }

    // Jumping to a particular transfer shows it in full context, so it drops the date range.
    LaunchedEffect(scrollToTransferId) {
        if (scrollToTransferId != null) dateRange = TransactionDateRange()
    }
    val displayedRunningBalances =
        if (showExcluded) runningBalances else runningBalances.filter { !it.isExcluded }

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
        LaunchedEffect(
            selectedAccountId,
            selectedCurrencyId,
            pageSize,
            scrollToTransferId,
            dateRange,
            refreshTrigger,
            externalRefreshTrigger,
        ) {
            runningBalances = emptyList()
            currentPagingInfo = null
            hasLoadedFirstPage = false
            firstPageFailed = false
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
                        dateRange.clipOlder(
                            transactionRepository.getRunningBalanceByAccountPaginated(
                                accountId = selectedAccountId,
                                pageSize = pageSize,
                                pagingInfo = dateRange.firstPageCursor(),
                                currencyId = selectedCurrencyId,
                            ),
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
                logger.e(expected) { "Failed to load transactions: ${expected.message}" }
                // The spinner waits on hasLoadedFirstPage, so a failure must end it too.
                firstPageFailed = true
                hasLoadedFirstPage = true
            } finally {
                isLoadingPage = false
                pageLoadGeneration++
            }
        }

        // Function to load next page
        suspend fun loadNextPage() {
            if (isLoadingPage || currentPagingInfo?.hasMore != true) return

            isLoadingPage = true
            try {
                val result =
                    dateRange.clipOlder(
                        transactionRepository.getRunningBalanceByAccountPaginated(
                            accountId = selectedAccountId,
                            pageSize = pageSize,
                            pagingInfo = currentPagingInfo,
                            currencyId = selectedCurrencyId,
                        ),
                    )
                runningBalances = runningBalances + result.items
                currentPagingInfo = result.pagingInfo
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is expected when user navigates away or scrolls fast
                // Rethrow to let coroutine machinery handle it properly
                throw e
            } catch (expected: Exception) {
                logger.e(expected) { "Failed to load more transactions: ${expected.message}" }
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
                val newer = dateRange.clipNewer(result.items)
                if (newer.isNotEmpty()) {
                    runningBalances = newer + runningBalances
                }
                hasPreviousPage = dateRange.hasNewer(result.items, newer, result.pagingInfo.hasMore)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (expected: Exception) {
                logger.e(expected) { "Failed to load previous transactions: ${expected.message}" }
            } finally {
                isLoadingPreviousPage = false
            }
        }

        val screenSizeClass = ScreenSizeClass.fromWidth(maxWidth)

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("accountTransactionsBack")) {
                    Text("← Back")
                }
                Text(
                    text = allAccounts.firstOrNull { it.id == selectedAccountId }?.name ?: "Account",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                allAccounts.firstOrNull { it.id == selectedAccountId }?.let { account ->
                    IconButton(onClick = { accountToEdit = account }) {
                        Text("⚙️")
                    }
                }
            }

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
                IconButton(
                    onClick = { showDates = !showDates },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Filter transactions by date"
                        },
                ) {
                    Text("\uD83D\uDCC5", style = MaterialTheme.typography.bodyLarge)
                }
                AssetFilterDropdown(
                    assets = accountAssets,
                    selectedAssetId = selectedCurrencyId?.id,
                    onSelected = { selectedCurrencyId = it?.let { asset -> CurrencyId(asset.id.id) } },
                )
            }

            if (showDates || dateRange.isActive) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DateRangeFilterFields(
                        from = dateRange.from,
                        to = dateRange.to,
                        onFromChange = { dateRange = dateRange.copy(from = it) },
                        onToChange = { dateRange = dateRange.copy(to = it) },
                        fromLabel = "Transactions from (date)",
                        toLabel = "Transactions to (date)",
                    )
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
                        text =
                            if (firstPageFailed) {
                                "Couldn't load these transactions."
                            } else if (dateRange.isActive) {
                                "No transactions in this period."
                            } else if (selectedCurrencyId != null) {
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
                    // Reconciliation column heading: blank; the per-row tick/cross icons are
                    // self-explanatory via their tooltips.
                    Spacer(modifier = Modifier.width(24.dp))
                    // Transaction type column heading: an icon whose tooltip legends the per-row icons.
                    Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = {
                                PlainTooltip {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Transaction type",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        TransactionKind.entries.forEach { kind ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Icon(
                                                    imageVector = kind.icon(),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Text(
                                                    text = kind.displayLabel(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "Transaction type",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
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
                    Spacer(modifier = Modifier.width(48.dp))
                }

                val listState = rememberLazyListState()

                // Scroll to specific transaction if requested. The scroll must run exactly once per
                // target: keying on pageLoadGeneration re-fires the effect after each first-page
                // (re)load — including the currency-filtered reload the first pass triggers — while
                // pagination prepends/appends don't re-fire it, so they can't yank the viewport back
                // to the anchor. The latch stops later reloads (e.g. after edits) from re-centering.
                var hasScrolledToTarget by remember(scrollToTransferId) { mutableStateOf(false) }
                LaunchedEffect(scrollToTransferId, pageLoadGeneration) {
                    if (hasScrolledToTarget) return@LaunchedEffect
                    scrollToTransferId?.let { targetTransferId ->
                        // Set highlighted transaction (TransferId implements TransactionId)
                        highlightedTransactionId = targetTransferId

                        // First find the transaction in unfiltered list to get its currency
                        val transaction = runningBalances.find { it.transactionId.id == targetTransferId.id }
                        if (transaction == null) {
                            // Absent from a fully loaded page means it doesn't exist in this account;
                            // latch anyway so pagination (gated on the latch) isn't blocked forever.
                            if (hasLoadedFirstPage && !isLoadingPage && runningBalances.isNotEmpty()) {
                                hasScrolledToTarget = true
                            }
                            return@let
                        }

                        // Set currency filter to match the transaction; that reloads the page, so the
                        // centering scroll happens on the next pass over the filtered list
                        val targetCurrencyId = CurrencyId(transaction.transactionAmount.asset.id.id)
                        if (selectedCurrencyId != targetCurrencyId) {
                            selectedCurrencyId = targetCurrencyId
                            return@let
                        }

                        // Find the index in the list the LazyColumn actually renders (currency-filtered
                        // pages minus hidden excluded rows) so the centering lands on the right row
                        val index =
                            displayedRunningBalances
                                .filter {
                                    it.transactionAmount.asset.id == transaction.transactionAmount.asset.id
                                }.indexOfFirst { it.transactionId.id == targetTransferId.id }

                        if (index < 0) {
                            // The target is loaded but not rendered (e.g. excluded while excluded rows
                            // are hidden) — give up so pagination (gated on the latch) isn't blocked.
                            hasScrolledToTarget = true
                            return@let
                        }
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
                        try {
                            listState.animateScrollToItem(index, centeringOffset)
                        } finally {
                            // Latch even when the animation is cancelled (e.g. the user grabs the
                            // list), otherwise auto-pagination would stay gated forever.
                            hasScrolledToTarget = true
                        }

                    }
                }

                // While a target scroll is pending, the list sits at position 0 (near the trigger
                // thresholds) and a prepend would shift item indices under the in-flight centering
                // animation, so hold off auto-pagination until the anchor scroll has run.
                val anchorScrollPending = scrollToTransferId != null && !hasScrolledToTarget

                // Trigger pagination when user scrolls near the end.
                // The loading/hasMore flags must NOT be keys of these effects: loadNextPage /
                // loadPreviousPage flip them, and a key change restarts the effect — cancelling the
                // very load it just started (withContext discards the result on cancellation even if
                // the query finished). Loading a page then only succeeded when the query beat the
                // next recomposition; once a boundary's query was slower than a frame, every retry
                // was cancelled and scrolling stopped dead. The flags are snapshot state, so the
                // collect body always reads their current values without them being keys.
                LaunchedEffect(listState, anchorScrollPending) {
                    if (anchorScrollPending) return@LaunchedEffect
                    snapshotFlow {
                        val layoutInfo = listState.layoutInfo
                        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        lastVisibleIndex?.let { it to layoutInfo.totalItemsCount }
                    }.collect { indexAndCount ->
                        val (lastVisibleIndex, totalItemsCount) = indexAndCount ?: return@collect
                        // Load more when within 10 items of the end
                        if (lastVisibleIndex >= totalItemsCount - 10 &&
                            !isLoadingPage &&
                            currentPagingInfo?.hasMore == true
                        ) {
                            loadNextPage()
                        }
                    }
                }

                // Trigger backward pagination when user scrolls near the beginning
                LaunchedEffect(listState, anchorScrollPending) {
                    if (anchorScrollPending) return@LaunchedEffect
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

                // Exchange accounts with imported orders get a shortcut to the per-account Orders list.
                val orderCount by rememberFlowAsStateWithSchemaErrorHandling(selectedAccountId, initial = 0L) {
                    exchangeOrderRepository.countOrdersByAccount(selectedAccountId)
                }
                if (orderCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                val name = allAccounts.firstOrNull { it.id == selectedAccountId }?.name ?: ""
                                onOrdersClick(selectedAccountId, name)
                            },
                            modifier = Modifier.testTag("ordersButton"),
                        ) {
                            Text("Orders ($orderCount)")
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.testTag("accountTransactionsList"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = displayedRunningBalances,
                            // A same-account trade (e.g. BTC→ETH inside one exchange account) yields two
                            // rows with the same transaction and account, one per asset leg — the asset
                            // code keeps their keys unique.
                            key = { "${it.transactionId}-${it.accountId}-${it.transactionAmount.asset.code}" },
                        ) { runningBalance ->
                            AccountTransactionCard(
                                runningBalance = runningBalance,
                                accounts = allAccounts,
                                screenSizeClass = screenSizeClass,
                                isHighlighted = highlightedTransactionId == runningBalance.transactionId,
                                onEditClick = { transfer ->
                                    // TransactionEditDialog is fiat-only (CurrencyPicker + currencyRepository
                                    // save path); opening it on a crypto-denominated transfer would corrupt
                                    // its asset. Only fiat transfers are editable here for now.
                                    if (transfer.amount.asset is Currency) {
                                        transactionIdToEdit = transfer.id
                                    }
                                },
                                onEditTradeClick = { tradeIdToEdit = it },
                                onAuditClick = onAuditClick,
                                onOrderLinkClick = onOrderLinkClick,
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
                                    selectedCurrencyId = CurrencyId(runningBalance.transactionAmount.asset.id.id)

                                    // Notify parent to switch to the clicked account
                                    onAccountIdChange(clickedAccountId)

                                    // Navigate to the clicked account (adds to navigation history),
                                    // scrolling to this same transfer as seen from the other account's side.
                                    val clickedAccount = allAccounts.find { it.id == clickedAccountId }
                                    if (clickedAccount != null) {
                                        onAccountClick(
                                            clickedAccountId,
                                            clickedAccount.name,
                                            CurrencyId(runningBalance.transactionAmount.asset.id.id),
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

    val editedTradeId = tradeIdToEdit
    if (editedTradeId != null && tradeRepository != null) {
        TradeExclusionDialog(
            tradeId = editedTradeId,
            tradeRepository = tradeRepository,
            maintenance = maintenance,
            onDismiss = { tradeIdToEdit = null },
            onSaved = { refreshTrigger++ },
        )
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
            holidayCalendarRepository = holidayCalendarRepository,
            // No onSaved refresh needed: allAccounts is collected from a Flow and updates automatically
            onDismiss = { accountToEdit = null },
        )
    }
}
