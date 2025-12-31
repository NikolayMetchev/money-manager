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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.compose.scrollbar.VerticalScrollbarForScrollState
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.TransferWithAttributes
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.audit.AttributeChange
import com.moneymanager.ui.audit.AuditEntryDiff
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.UpdateNewValues
import com.moneymanager.ui.audit.computeAuditDiff
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import nl.jacobras.humanreadable.HumanReadable
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = logging()

private val ACCOUNT_COLUMN_MIN_WIDTH = 100.dp

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
    transferSourceRepository: TransferSourceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    auditRepository: AuditRepository,
    attributeTypeRepository: AttributeTypeRepository,
    transferAttributeRepository: TransferAttributeRepository,
    maintenanceService: DatabaseMaintenanceService,
    currentDeviceId: Long? = null,
    onAccountIdChange: (AccountId) -> Unit = {},
    onCurrencyIdChange: (CurrencyId?) -> Unit = {},
    scrollToTransferId: TransferId? = null,
    externalRefreshTrigger: Int = 0,
) {
    // Audit transaction state - when set, shows full-screen audit view
    var transactionIdToAudit by remember { mutableStateOf<TransferId?>(null) }

    // Show full-screen audit view if a transaction is selected for audit
    transactionIdToAudit?.let { transferId ->
        TransactionAuditScreen(
            transferId = transferId,
            auditRepository = auditRepository,
            accountRepository = accountRepository,
            transactionRepository = transactionRepository,
            transferAttributeRepository = transferAttributeRepository,
            currentDeviceId = currentDeviceId,
            onBack = { transactionIdToAudit = null },
        )
        return
    }

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
    var runningBalances by remember { mutableStateOf<List<AccountRow>>(emptyList()) }
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
            } catch (e: Exception) {
                logger.error(e) { "Failed to load transactions: ${e.message}" }
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
            } catch (e: Exception) {
                logger.error(e) { "Failed to load more transactions: ${e.message}" }
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
            } catch (e: Exception) {
                logger.error(e) { "Failed to load previous transactions: ${e.message}" }
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
                                onAuditClick = { transferId ->
                                    transactionIdToAudit = transferId
                                },
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
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            transferAttributeRepository = transferAttributeRepository,
            maintenanceService = maintenanceService,
            onDismiss = { transactionIdToEdit = null },
            onSaved = { refreshTrigger++ },
        )
    }
}

@Composable
fun AccountTransactionCard(
    runningBalance: AccountRow,
    accounts: List<Account>,
    screenSizeClass: ScreenSizeClass,
    isHighlighted: Boolean = false,
    onAccountClick: (AccountId) -> Unit = {},
    onEditClick: (Transfer) -> Unit = {},
    onAuditClick: (TransferId) -> Unit = {},
) {
    // Determine which account to display based on the current view
    // The account column should show the OTHER account in the transaction
    // runningBalance.accountId tells us which account's perspective we're viewing
    val otherAccount =
        when {
            // If current account is the source, show the target (where money went)
            runningBalance.sourceAccountId == runningBalance.accountId -> accounts.find { it.id == runningBalance.targetAccountId }
            // If current account is the target, show the source (where money came from)
            runningBalance.targetAccountId == runningBalance.accountId -> accounts.find { it.id == runningBalance.sourceAccountId }
            // Fallback: shouldn't happen, but if neither matches, show nothing
            else -> null
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
                val time = dateTime.time
                val millis = time.nanosecond / 1_000_000
                val timeText =
                    "${time.hour.toString().padStart(2, '0')}:" +
                        "${time.minute.toString().padStart(2, '0')}:" +
                        "${time.second.toString().padStart(2, '0')}." +
                        millis.toString().padStart(3, '0')
                Text(
                    text = timeText,
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
            if (runningBalance.description.isNotBlank()) {
                Text(
                    text = runningBalance.description,
                    style = cellStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    autoSize = cellAutoSize,
                    modifier = Modifier.weight(0.25f).padding(horizontal = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(0.25f))
            }

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

            // Edit button - reconstruct Transfer from AccountRow
            IconButton(
                onClick = {
                    // Reconstruct Transfer object from AccountRow fields
                    // Note: Amount needs to be positive value from the materialized view
                    val amount =
                        if (runningBalance.transactionAmount.amount < 0) {
                            Money(-runningBalance.transactionAmount.amount, runningBalance.transactionAmount.currency)
                        } else {
                            runningBalance.transactionAmount
                        }
                    val transfer =
                        Transfer(
                            id = runningBalance.transactionId as TransferId,
                            timestamp = runningBalance.timestamp,
                            description = runningBalance.description,
                            sourceAccountId = runningBalance.sourceAccountId,
                            targetAccountId = runningBalance.targetAccountId,
                            amount = amount,
                        )
                    onEditClick(transfer)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = "\u270F\uFE0F",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Audit button - show audit history for this transaction
            IconButton(
                onClick = {
                    onAuditClick(runningBalance.transactionId as TransferId)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = "\uD83D\uDCDC",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntryDialog(
    transactionRepository: TransactionRepository,
    transferSourceRepository: TransferSourceRepository,
    transferSourceQueries: TransferSourceQueries,
    deviceRepository: DeviceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    transferAttributeRepository: TransferAttributeRepository,
    maintenanceService: DatabaseMaintenanceService,
    preSelectedSourceAccountId: AccountId? = null,
    preSelectedCurrencyId: CurrencyId? = null,
    onDismiss: () -> Unit,
    onTransactionCreated: () -> Unit = {},
) {
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

    val scope = rememberSchemaAwareCoroutineScope()

    // Attribute state
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    // EditableAttribute represents the current state of each attribute in the UI
    // key: a negative temp id for new ones
    // value: Pair(attributeTypeName, value)
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var nextTempId by remember { mutableStateOf(-1L) }

    // Load existing attribute types for autocomplete
    LaunchedEffect(Unit) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }

    // Compute form validity - all required fields must be populated
    val isAmountValid = amount.isNotBlank() && amount.toDoubleOrNull()?.let { it > 0 } == true
    val isFormValid =
        sourceAccountId != null &&
            targetAccountId != null &&
            sourceAccountId != targetAccountId &&
            currencyId != null &&
            isAmountValid &&
            description.isNotBlank()

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
                // Source Account Picker
                AccountPicker(
                    selectedAccountId = sourceAccountId,
                    onAccountSelected = { sourceAccountId = it },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    excludeAccountId = targetAccountId,
                    isError = sourceAccountId == null,
                )

                // Target Account Picker
                AccountPicker(
                    selectedAccountId = targetAccountId,
                    onAccountSelected = { targetAccountId = it },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    excludeAccountId = sourceAccountId,
                    isError = targetAccountId == null || targetAccountId == sourceAccountId,
                )

                // Currency Picker
                CurrencyPicker(
                    selectedCurrencyId = currencyId,
                    onCurrencySelected = { currencyId = it },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isSaving,
                    isError = currencyId == null,
                )

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
                    isError = !isAmountValid,
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = description.isBlank(),
                )

                // Attributes Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Attributes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Display editable attributes
                    editableAttributes.forEach { (id, pair) ->
                        val (typeName, value) = pair
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Attribute type selector
                            AttributeTypeField(
                                value = typeName,
                                onValueChange = { newTypeName ->
                                    editableAttributes = editableAttributes + (id to Pair(newTypeName, value))
                                },
                                existingTypes = existingAttributeTypes,
                                enabled = !isSaving,
                                modifier = Modifier.weight(0.4f),
                            )
                            // Attribute value field
                            OutlinedTextField(
                                value = value,
                                onValueChange = { newValue ->
                                    editableAttributes = editableAttributes + (id to Pair(typeName, newValue))
                                },
                                label = { Text("Value") },
                                modifier = Modifier.weight(0.5f),
                                singleLine = true,
                                enabled = !isSaving,
                            )
                            // Delete button
                            IconButton(
                                onClick = {
                                    editableAttributes = editableAttributes - id
                                },
                                enabled = !isSaving,
                            ) {
                                Text(
                                    text = "X",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }

                    // Add new attribute button
                    TextButton(
                        onClick = {
                            editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                            nextTempId--
                        },
                        enabled = !isSaving,
                    ) {
                        Text("+ Add Attribute")
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
                    // Form is validated via isFormValid, button is disabled when invalid
                    // This check is a safety net
                    if (isFormValid) {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                // Get the currency object from repository
                                val currency =
                                    currencyRepository.getCurrencyById(currencyId!!).first()
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
                                        amount = Money.fromDisplayValue(amount, currency),
                                    )

                                // Prepare attributes with their type IDs
                                val attributesToSave =
                                    editableAttributes
                                        .filter { (_, pair) ->
                                            val (typeName, value) = pair
                                            typeName.isNotBlank() && value.isNotBlank()
                                        }
                                        .map { (_, pair) ->
                                            val (typeName, value) = pair
                                            val typeId = attributeTypeRepository.getOrCreate(typeName.trim())
                                            NewAttribute(typeId, value.trim())
                                        }

                                // Create transfer with attributes and source in one transaction
                                val deviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())
                                transactionRepository.createTransfers(
                                    transfersWithAttributes = listOf(TransferWithAttributes(transfer, attributesToSave)),
                                    sourceRecorder = ManualSourceRecorder(transferSourceQueries, deviceId),
                                )

                                maintenanceService.refreshMaterializedViews()

                                onTransactionCreated()
                                onDismiss()
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to create transaction: ${e.message}" }
                                errorMessage = "Failed to create transaction: ${e.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = isFormValid && !isSaving,
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
    transferSourceRepository: TransferSourceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    transferAttributeRepository: TransferAttributeRepository,
    maintenanceService: DatabaseMaintenanceService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
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
    val originalSecond = remember { transactionDateTime.second }
    val originalNanosecond = remember { transactionDateTime.nanosecond }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()

    // Attribute state
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var originalAttributes by remember { mutableStateOf<List<TransferAttribute>>(emptyList()) }
    var isLoadingAttributes by remember { mutableStateOf(true) }

    // EditableAttribute represents the current state of each attribute in the UI
    // key: a stable identifier (original attribute id or a negative temp id for new ones)
    // value: Pair(attributeTypeName, value)
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var nextTempId by remember { mutableStateOf(-1L) }

    // Load existing attribute types and attributes for this transaction
    LaunchedEffect(transaction.id) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }
    LaunchedEffect(transaction.id) {
        transferAttributeRepository.getByTransaction(transaction.id).first().let { attrs ->
            originalAttributes = attrs
            editableAttributes =
                attrs.associate { attr ->
                    attr.id to Pair(attr.attributeType.name, attr.value)
                }
        }
        isLoadingAttributes = false
    }

    // Helper to check if attributes have changed
    fun hasAttributeChanges(): Boolean {
        val originalMap = originalAttributes.associate { it.id to Pair(it.attributeType.name, it.value) }
        // Check if any new attributes were added (negative IDs)
        if (editableAttributes.keys.any { it < 0 }) return true
        // Check if any original attributes were removed
        if (originalMap.keys.any { it !in editableAttributes.keys }) return true
        // Check if any attribute values changed
        return editableAttributes.any { (id, pair) ->
            val original = originalMap[id]
            original == null || original != pair
        }
    }

    // Check if any field has changed from the original transaction
    val hasChanges =
        remember(
            sourceAccountId,
            targetAccountId,
            currencyId,
            amount,
            description,
            selectedDate,
            selectedHour,
            selectedMinute,
            editableAttributes,
            originalAttributes,
        ) {
            sourceAccountId != transaction.sourceAccountId ||
                targetAccountId != transaction.targetAccountId ||
                currencyId != transaction.amount.currency.id ||
                amount != transaction.amount.toDisplayValue().toString() ||
                description != transaction.description ||
                selectedDate != transactionDateTime.date ||
                selectedHour != transactionDateTime.hour ||
                selectedMinute != transactionDateTime.minute ||
                hasAttributeChanges()
        }

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
                // Source Account Picker
                AccountPicker(
                    selectedAccountId = sourceAccountId,
                    onAccountSelected = { sourceAccountId = it },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    excludeAccountId = targetAccountId,
                )

                // Target Account Picker
                AccountPicker(
                    selectedAccountId = targetAccountId,
                    onAccountSelected = { targetAccountId = it },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    excludeAccountId = sourceAccountId,
                )

                // Currency Picker
                CurrencyPicker(
                    selectedCurrencyId = currencyId,
                    onCurrencySelected = { currencyId = it },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isSaving,
                )

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

                // Attributes Section
                if (isLoadingAttributes) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Attributes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Display editable attributes
                        editableAttributes.forEach { (id, pair) ->
                            val (typeName, value) = pair
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Attribute type selector
                                AttributeTypeField(
                                    value = typeName,
                                    onValueChange = { newTypeName ->
                                        editableAttributes = editableAttributes + (id to Pair(newTypeName, value))
                                    },
                                    existingTypes = existingAttributeTypes,
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(0.4f),
                                )
                                // Attribute value field
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { newValue ->
                                        editableAttributes = editableAttributes + (id to Pair(typeName, newValue))
                                    },
                                    label = { Text("Value") },
                                    modifier = Modifier.weight(0.5f),
                                    singleLine = true,
                                    enabled = !isSaving,
                                )
                                // Delete button
                                IconButton(
                                    onClick = {
                                        editableAttributes = editableAttributes - id
                                    },
                                    enabled = !isSaving,
                                ) {
                                    Text(
                                        text = "X",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }

                        // Add new attribute button
                        TextButton(
                            onClick = {
                                editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                                nextTempId--
                            },
                            enabled = !isSaving,
                        ) {
                            Text("+ Add Attribute")
                        }
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
                                    // Get the currency object from repository
                                    val currency =
                                        currencyRepository.getCurrencyById(currencyId).first()
                                            ?: throw IllegalStateException("Currency not found")

                                    val timestamp =
                                        selectedDate
                                            .atTime(selectedHour, selectedMinute, originalSecond, originalNanosecond)
                                            .toInstant(TimeZone.currentSystemDefault())

                                    // Check if transfer fields actually changed
                                    val transferFieldsChanged =
                                        sourceAccountId != transaction.sourceAccountId ||
                                            targetAccountId != transaction.targetAccountId ||
                                            currencyId != transaction.amount.currency.id ||
                                            amount != transaction.amount.toDisplayValue().toString() ||
                                            description.trim() != transaction.description ||
                                            timestamp != transaction.timestamp

                                    // Build the transfer object if fields changed
                                    val updatedTransfer =
                                        if (transferFieldsChanged) {
                                            Transfer(
                                                id = transaction.id,
                                                timestamp = timestamp,
                                                description = description.trim(),
                                                sourceAccountId = sourceAccountId,
                                                targetAccountId = targetAccountId,
                                                amount = Money.fromDisplayValue(amount, currency),
                                            )
                                        } else {
                                            null
                                        }

                                    // Build attribute change data structures
                                    val originalIds = originalAttributes.map { it.id }.toSet()
                                    val editableIds = editableAttributes.keys
                                    val deletedAttributeIds = originalIds - editableIds

                                    // Build updated attributes map (id -> NewAttribute)
                                    val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                                    editableAttributes.filter { (id, _) -> id > 0 }.forEach { (id, pair) ->
                                        val (typeName, value) = pair
                                        val original = originalAttributes.find { it.id == id }
                                        if (original != null) {
                                            val typeChanged = original.attributeType.name != typeName
                                            val valueChanged = original.value != value
                                            if (typeChanged || valueChanged) {
                                                val typeId = attributeTypeRepository.getOrCreate(typeName)
                                                updatedAttributes[id] = NewAttribute(typeId, value)
                                            }
                                        }
                                    }

                                    // Build new attributes list
                                    val newAttributes = mutableListOf<NewAttribute>()
                                    editableAttributes.filter { (id, _) -> id < 0 }.forEach { (_, pair) ->
                                        val (typeName, value) = pair
                                        if (typeName.isNotBlank() && value.isNotBlank()) {
                                            val typeId = attributeTypeRepository.getOrCreate(typeName)
                                            newAttributes.add(NewAttribute(typeId, value))
                                        }
                                    }

                                    // Use the atomic method to update transfer and attributes together
                                    // This ensures only ONE revision bump even if both change
                                    transactionRepository.updateTransfer(
                                        transfer = updatedTransfer,
                                        deletedAttributeIds = deletedAttributeIds,
                                        updatedAttributes = updatedAttributes,
                                        newAttributes = newAttributes,
                                        transactionId = transaction.id,
                                    )

                                    // Record manual source for this update
                                    val updated =
                                        transactionRepository.getTransactionById(transaction.id.id).first()
                                    if (updated != null) {
                                        transferSourceRepository.recordManualSource(
                                            transactionId = updated.id,
                                            revisionId = updated.revisionId,
                                            deviceInfo = getDeviceInfo(),
                                        )
                                    }

                                    maintenanceService.refreshMaterializedViews()

                                    onSaved()
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
                enabled = !isSaving && hasChanges,
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
fun TransactionAuditScreen(
    transferId: TransferId,
    auditRepository: AuditRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    transferAttributeRepository: TransferAttributeRepository,
    currentDeviceId: Long? = null,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<TransferAuditEntry>>(emptyList()) }
    var currentTransfer by remember { mutableStateOf<Transfer?>(null) }
    var currentAttributes by remember { mutableStateOf<List<TransferAttribute>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    LaunchedEffect(transferId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForTransferWithSource(transferId)
            val transfer = transactionRepository.getTransactionById(transferId.id).first()
            currentTransfer = transfer
            // Load current attributes if transfer exists
            if (transfer != null) {
                currentAttributes =
                    transferAttributeRepository
                        .getByTransaction(transferId)
                        .first()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load audit history: ${e.message}" }
            errorMessage = "Failed to load audit history: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Audit History: $transferId",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (auditEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No audit history found for this transaction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Compute diffs from audit entries
            // For UPDATE entries: entry stores OLD values, NEW values come from:
            // - Current transfer (if this is the most recent entry, index 0)
            // - Next audit entry's values (entries[index-1]) for older updates
            // Attribute changes are now stored directly in TransferAttributeAudit,
            // so we don't need to compare attributes between entries.
            val auditDiffs =
                remember(auditEntries, currentTransfer) {
                    val transfer = currentTransfer
                    auditEntries.mapIndexed { index, entry ->
                        val newValuesForUpdate =
                            when {
                                entry.auditType != AuditType.UPDATE -> null
                                index == 0 && transfer != null ->
                                    UpdateNewValues.fromTransfer(transfer)
                                index > 0 ->
                                    UpdateNewValues.fromAuditEntry(auditEntries[index - 1])
                                else -> null
                            }
                        computeAuditDiff(entry, newValuesForUpdate)
                    }
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.auditId }) { diff ->
                        AuditDiffCard(
                            diff = diff,
                            accounts = accounts,
                            currentDeviceId = currentDeviceId,
                        )
                    }
                }
                VerticalScrollbarForLazyList(
                    lazyListState = auditListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun AuditDiffCard(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: Long? = null,
) {
    val auditDateTime = diff.auditTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())

    val (headerColor, headerText, containerColor) =
        when (diff.auditType) {
            AuditType.INSERT ->
                Triple(
                    MaterialTheme.colorScheme.primary,
                    "Created",
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            AuditType.UPDATE ->
                Triple(
                    MaterialTheme.colorScheme.tertiary,
                    "Updated",
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                )
            AuditType.DELETE ->
                Triple(
                    MaterialTheme.colorScheme.error,
                    "Deleted",
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                )
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: Type + Revision + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = headerColor,
                    )
                    Text(
                        text = "Rev ${diff.revisionId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${auditDateTime.date} ${auditDateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Content varies by audit type
            when (diff.auditType) {
                AuditType.INSERT -> InsertDiffContent(diff, accounts, currentDeviceId)
                AuditType.UPDATE -> UpdateDiffContent(diff, accounts, currentDeviceId)
                AuditType.DELETE -> DeleteDiffContent(diff, accounts, currentDeviceId)
            }
        }
    }
}

@Composable
private fun InsertDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: Long? = null,
) {
    val transactionDateTime = diff.timestamp.value().toLocalDateTime(TimeZone.currentSystemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Created with:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}")
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts))
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts))
        FieldValueRow("Amount", formatAmount(diff.amount.value()))
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" })
        AttributesSection(diff.attributeChanges)
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId)
    }
}

@Composable
private fun UpdateDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: Long? = null,
) {
    val hasAnyChanges = diff.hasChanges

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!hasAnyChanges) {
            Text(
                text = "No visible changes recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Changed:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val timestampChange = diff.timestamp
            if (timestampChange is FieldChange.Changed) {
                val oldDateTime = timestampChange.oldValue.toLocalDateTime(TimeZone.currentSystemDefault())
                val newDateTime = timestampChange.newValue.toLocalDateTime(TimeZone.currentSystemDefault())
                val timeDiff = formatTimeDiff(timestampChange.oldValue, timestampChange.newValue)
                FieldChangeRow(
                    label = "Date",
                    oldValue = "${oldDateTime.date} ${oldDateTime.time}",
                    newValue = "${newDateTime.date} ${newDateTime.time}",
                    suffix = "($timeDiff)",
                )
            }
            val sourceChange = diff.sourceAccountId
            if (sourceChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "From",
                    oldValue = resolveAccountName(sourceChange.oldValue, accounts),
                    newValue = resolveAccountName(sourceChange.newValue, accounts),
                )
            }
            val targetChange = diff.targetAccountId
            if (targetChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "To",
                    oldValue = resolveAccountName(targetChange.oldValue, accounts),
                    newValue = resolveAccountName(targetChange.newValue, accounts),
                )
            }
            val amountChange = diff.amount
            if (amountChange is FieldChange.Changed) {
                val amountDiff = amountChange.newValue - amountChange.oldValue
                val sign = if (amountDiff.isPositive()) "+" else ""
                FieldChangeRow(
                    label = "Amount",
                    oldValue = formatAmount(amountChange.oldValue),
                    newValue = formatAmount(amountChange.newValue),
                    suffix = "($sign${formatAmount(amountDiff)})",
                )
            }
            val descriptionChange = diff.description
            if (descriptionChange is FieldChange.Changed) {
                FieldChangeRow(
                    label = "Description",
                    oldValue = descriptionChange.oldValue.ifBlank { "(none)" },
                    newValue = descriptionChange.newValue.ifBlank { "(none)" },
                )
            }
            // Show attribute changes (added, removed, changed - not unchanged)
            val significantAttrChanges = diff.attributeChanges.filter { it !is AttributeChange.Unchanged }
            if (significantAttrChanges.isNotEmpty()) {
                AttributeChangesSection(significantAttrChanges)
            }
        }
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId)
    }
}

@Composable
private fun DeleteDiffContent(
    diff: AuditEntryDiff,
    accounts: List<Account>,
    currentDeviceId: Long? = null,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val transactionDateTime = diff.timestamp.value().toLocalDateTime(TimeZone.currentSystemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Deleted (final values):",
            style = MaterialTheme.typography.labelMedium,
            color = errorColor.copy(alpha = 0.8f),
        )
        FieldValueRow("Date", "${transactionDateTime.date} ${transactionDateTime.time}", errorColor)
        FieldValueRow("From", resolveAccountName(diff.sourceAccountId.value(), accounts), errorColor)
        FieldValueRow("To", resolveAccountName(diff.targetAccountId.value(), accounts), errorColor)
        FieldValueRow("Amount", formatAmount(diff.amount.value()), errorColor)
        FieldValueRow("Description", diff.description.value().ifBlank { "(none)" }, errorColor)
        AttributesSection(diff.attributeChanges, errorColor)
        SourceInfoSection(diff.source, currentDeviceId = currentDeviceId, labelColor = errorColor.copy(alpha = 0.8f))
    }
}

@Composable
private fun FieldValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun FieldChangeRow(
    label: String,
    oldValue: String,
    newValue: String,
    suffix: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )

        // Old value with strikethrough
        Text(
            text = oldValue,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.LineThrough,
                ),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        )

        // Arrow
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // New value
        Text(
            text = newValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Optional suffix (e.g., diff in brackets)
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttributesSection(
    attributeChanges: List<AttributeChange>,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (attributeChanges.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Attributes:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attributeChanges.forEach { change ->
            val value =
                when (change) {
                    is AttributeChange.Added -> change.value
                    is AttributeChange.Removed -> change.value
                    is AttributeChange.Changed -> change.newValue
                    is AttributeChange.ModifiedFrom -> change.oldValue
                    is AttributeChange.Unchanged -> change.value
                }
            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${change.attributeTypeName}:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                )
            }
        }
    }
}

@Composable
private fun AttributeChangesSection(attributeChanges: List<AttributeChange>) {
    if (attributeChanges.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        attributeChanges.forEach { change ->
            when (change) {
                is AttributeChange.Added -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "+${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = change.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is AttributeChange.Removed -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "-${change.attributeTypeName}:",
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = change.value,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                }
                is AttributeChange.Changed -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.oldValue,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "\u2192",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.newValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is AttributeChange.ModifiedFrom -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${change.attributeTypeName}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = change.oldValue,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "\u2192 ?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is AttributeChange.Unchanged -> {
                    // Don't show unchanged attributes in the changes section
                }
            }
        }
    }
}

private fun resolveAccountName(
    accountId: AccountId,
    accounts: List<Account>,
): String = accounts.find { it.id == accountId }?.name ?: "#${accountId.id}"

private fun formatTimeDiff(
    oldTimestamp: Instant,
    newTimestamp: Instant,
): String {
    val duration = newTimestamp - oldTimestamp
    val sign = if (duration.isPositive()) "+" else "-"
    return "$sign${HumanReadable.duration(duration.absoluteValue)}"
}

@Composable
private fun SourceInfoSection(
    source: TransferSource?,
    currentDeviceId: Long? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (source == null) return

    val isThisDevice = currentDeviceId != null && source.deviceId == currentDeviceId

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )

        when (source.sourceType) {
            SourceType.MANUAL -> {
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Manual (Desktop)$thisDeviceSuffix")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)$thisDeviceSuffix")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
            SourceType.CSV_IMPORT -> {
                val csvSource = source.csvSource
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                if (csvSource != null) {
                    val fileName = csvSource.fileName ?: "Unknown file"
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix")
                    FieldValueRow("File", fileName)
                    FieldValueRow("Row", (csvSource.rowIndex + 1).toString())
                } else {
                    FieldValueRow("Origin", "CSV Import$thisDeviceSuffix")
                }
                // Show device info for CSV imports too
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
            SourceType.SAMPLE_GENERATOR -> {
                val deviceInfo = source.deviceInfo
                val thisDeviceSuffix = if (isThisDevice) " (This Device)" else ""
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)$thisDeviceSuffix")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)$thisDeviceSuffix")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                }
            }
        }
    }
}

/**
 * A dropdown field for selecting or entering an attribute type name.
 * Shows existing types in a dropdown, with the option to type a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttributeTypeField(
    value: String,
    onValueChange: (String) -> Unit,
    existingTypes: List<AttributeType>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var textValue by remember(value) { mutableStateOf(value) }

    // Filter suggestions based on current text
    val suggestions =
        remember(textValue, existingTypes) {
            if (textValue.isBlank()) {
                existingTypes.map { it.name }
            } else {
                existingTypes
                    .map { it.name }
                    .filter { it.contains(textValue, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                onValueChange(newValue)
                expanded = true
            },
            label = { Text("Type") },
            trailingIcon = {
                if (existingTypes.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
        )

        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { typeName ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(typeName)
                                if (typeName == value) {
                                    Text(
                                        "\u2713",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        onClick = {
                            textValue = typeName
                            onValueChange(typeName)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
