@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CryptoCatalogRefresher
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.StrategySyncController
import com.moneymanager.remotestorage.sync.SyncState
import com.moneymanager.remotestorage.sync.SyncStatus
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.ui.background.BackgroundTaskPanel
import com.moneymanager.ui.background.LocalBackgroundTaskManager
import com.moneymanager.ui.background.rememberBackgroundTaskManager
import com.moneymanager.ui.components.DefaultCurrencyInitDialog
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.navigation.NavigationHistory
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.navigation.ScreenSavedStateConfiguration
import com.moneymanager.ui.navigation.mouseButtonNavigation
import com.moneymanager.ui.screens.AccountAuditScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.ApiConnectScreen
import com.moneymanager.ui.screens.ApiSessionTrafficScreen
import com.moneymanager.ui.screens.AssetsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CategoryAuditScreen
import com.moneymanager.ui.screens.CsvImportDetailScreen
import com.moneymanager.ui.screens.CurrencyAuditScreen
import com.moneymanager.ui.screens.DatabaseSizeBreakdownScreen
import com.moneymanager.ui.screens.ImportDirectoryAuditScreen
import com.moneymanager.ui.screens.ImportsScreen
import com.moneymanager.ui.screens.PeopleScreen
import com.moneymanager.ui.screens.PersonAuditScreen
import com.moneymanager.ui.screens.QifImportDetailScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.StrategyCatalogScreen
import com.moneymanager.ui.screens.apistrategy.ApiImportStrategyAuditScreen
import com.moneymanager.ui.screens.apistrategy.ApiStrategiesScreen
import com.moneymanager.ui.screens.apistrategy.editor.ApiStrategyEditorScreen
import com.moneymanager.ui.screens.csvstrategy.CsvImportStrategyAuditScreen
import com.moneymanager.ui.screens.csvstrategy.CsvStrategiesScreen
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorScreen
import com.moneymanager.ui.screens.orders.ExchangeOrderAuditScreen
import com.moneymanager.ui.screens.orders.OrderDetailScreen
import com.moneymanager.ui.screens.orders.OrdersScreen
import com.moneymanager.ui.screens.qif.QifStrategyEditorScreen
import com.moneymanager.ui.screens.transactions.AccountTransactionsScreen
import com.moneymanager.ui.screens.transactions.TradeEntryDialog
import com.moneymanager.ui.screens.transactions.TransactionAuditScreen
import com.moneymanager.ui.screens.transactions.TransactionEditDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    appVersion: AppVersion,
    databaseLocation: DbLocation,
    services: AppServices,
    onRequestSwitchDatabase: (DbLocation) -> Unit,
    onReloadFromRemote: () -> Unit = {},
    remoteController: RemoteDatabaseController? = null,
    database: MoneyManagerDatabaseWrapper? = null,
    strategySyncController: StrategySyncController? = null,
    strategyCatalogController: StrategyCatalogController? = null,
    importFileSourceFactory: ImportFileSourceFactory? = null,
    driveFolderBrowser: DriveFolderBrowser? = null,
    cryptoCatalogRefresher: CryptoCatalogRefresher? = null,
) {
    ProvideSchemaAwareScope {
        val scope = rememberSchemaAwareCoroutineScope()
        val backgroundTaskManager = rememberBackgroundTaskManager(scope)
        val backStack = rememberNavBackStack(ScreenSavedStateConfiguration, Screen.Accounts())
        val navigationHistory = remember(backStack) { NavigationHistory(backStack) }
        val currentScreen = navigationHistory.currentScreen
        var showTransactionDialog by remember { mutableStateOf(false) }
        var showTradeDialog by remember { mutableStateOf(false) }
        var showAddMenu by remember { mutableStateOf(false) }
        var preSelectedAccountId by remember { mutableStateOf<AccountId?>(null) }
        var currentlyViewedAccountId by remember { mutableStateOf<AccountId?>(null) }
        var preSelectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
        var currentlyViewedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
        var transactionRefreshTrigger by remember { mutableStateOf(0) }

        // Multi-device sync state: when the remote is ahead, editing is locked until the user downloads.
        val syncState by (remoteController?.syncState ?: remember { MutableStateFlow(SyncState()) }).collectAsState()
        val editingLocked = syncState.editingLocked
        var showConflictDownloadConfirm by remember { mutableStateOf(false) }

        var defaultCurrencyLoaded by remember { mutableStateOf(false) }
        val defaultCurrencyId by services.settings.settingsRepository
            .getDefaultCurrencyId()
            .onEach { defaultCurrencyLoaded = true }
            .collectAsStateWithSchemaErrorHandling(initial = null)

        // Use schema-error-aware collection for flows that may fail on old databases
        val accounts by services.accounts.accountRepository
            .getAllAccounts()
            .collectAsStateWithSchemaErrorHandling(initial = emptyList())

        // Opens the account view for a clicked transfer, scrolled to it: money flows into the target
        // account for positive amounts and out of the source account for negative ones.
        fun navigateToTransferAccount(
            transferId: TransferId,
            isPositiveAmount: Boolean,
        ) {
            scope.launch {
                val transfer =
                    services.transactions.transactionRepository
                        .getTransactionById(transferId.id)
                        .first()
                        ?: return@launch
                val accountId = if (isPositiveAmount) transfer.targetAccountId else transfer.sourceAccountId
                val account = accounts.find { a -> a.id == accountId }
                navigationHistory.navigateTo(
                    Screen.AccountTransactions(
                        accountId = accountId,
                        accountName = account?.name ?: accountId.toString(),
                        scrollToTransferId = transferId,
                    ),
                )
            }
        }

        MaterialTheme {
            CompositionLocalProvider(
                LocalBackgroundTaskManager provides backgroundTaskManager,
                LocalDeviceId provides services.deviceId,
                LocalImportEngine provides services.transactions.importEngine,
            ) {
                Scaffold(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .imePadding()
                            .mouseButtonNavigation(
                                onBack = { navigationHistory.navigateBack() },
                                onForward = { navigationHistory.navigateForward() },
                            ),
                    topBar = {
                        if (currentScreen !is Screen.AccountTransactions) {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(currentScreen.title)
                                        Text(
                                            text = "v$appVersion",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        )
                                        Text(
                                            text = "Database: $databaseLocation",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                },
                                colors =
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDCB0") },
                                label = { Text("Accounts") },
                                selected =
                                    currentScreen is Screen.Accounts ||
                                        currentScreen is Screen.AccountTransactions ||
                                        currentScreen is Screen.ExchangeOrders ||
                                        currentScreen is Screen.ExchangeOrderDetail ||
                                        currentScreen is Screen.ExchangeOrderAuditHistory,
                                onClick = { navigationHistory.navigateTo(Screen.Accounts()) },
                            )
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDCB1") },
                                label = { Text("Assets") },
                                selected = currentScreen is Screen.Currencies,
                                onClick = { navigationHistory.navigateTo(Screen.Currencies) },
                            )
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDCC1") },
                                label = { Text("Categories") },
                                selected = currentScreen is Screen.Categories,
                                onClick = { navigationHistory.navigateTo(Screen.Categories) },
                            )
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDC65") },
                                label = { Text("People") },
                                selected = currentScreen is Screen.People || currentScreen is Screen.PeopleScroll,
                                onClick = { navigationHistory.navigateTo(Screen.People) },
                            )
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDCC4") },
                                label = { Text("Imports") },
                                selected =
                                    currentScreen is Screen.Imports ||
                                        currentScreen is Screen.CsvImportDetail ||
                                        currentScreen is Screen.QifImportDetail ||
                                        currentScreen is Screen.QifStrategyEditor ||
                                        currentScreen is Screen.CsvStrategies ||
                                        currentScreen is Screen.CsvStrategyEditor ||
                                        currentScreen is Screen.ApiStrategies ||
                                        currentScreen is Screen.ApiStrategyEditor ||
                                        currentScreen is Screen.ApiStrategyAuditHistory ||
                                        currentScreen is Screen.ApiSessionTraffic ||
                                        currentScreen is Screen.ConnectApi,
                                onClick = {
                                    navigationHistory.navigateTo(
                                        Screen.Imports(
                                            when (currentScreen) {
                                                is Screen.ApiSessionTraffic, is Screen.ConnectApi,
                                                is Screen.ApiStrategies, is Screen.ApiStrategyEditor,
                                                is Screen.ApiStrategyAuditHistory,
                                                -> ImportTab.API
                                                is Screen.QifImportDetail, is Screen.QifStrategyEditor -> ImportTab.QIF
                                                is Screen.Imports -> currentScreen.tab
                                                else -> ImportTab.DIRECTORIES
                                            },
                                        ),
                                    )
                                },
                            )
                            NavigationBarItem(
                                icon = { Text("\u2699\uFE0F") },
                                label = { Text("Settings") },
                                selected = currentScreen is Screen.Settings || currentScreen is Screen.DatabaseSizeBreakdown,
                                onClick = { navigationHistory.navigateTo(Screen.Settings) },
                            )
                        }
                    },
                    floatingActionButton = {
                        // Only show transaction FAB on screens where it makes sense
                        val showTransactionFab =
                            currentScreen is Screen.Accounts ||
                                currentScreen is Screen.AccountTransactions ||
                                currentScreen is Screen.Categories
                        if (showTransactionFab && !editingLocked) {
                            Box {
                                FloatingActionButton(onClick = { showAddMenu = true }) {
                                    Text("+", style = MaterialTheme.typography.headlineLarge)
                                }
                                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("New transaction") },
                                        onClick = {
                                            showAddMenu = false
                                            preSelectedAccountId = currentlyViewedAccountId
                                            preSelectedCurrencyId = currentlyViewedCurrencyId ?: defaultCurrencyId
                                            showTransactionDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New trade") },
                                        onClick = {
                                            showAddMenu = false
                                            showTradeDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { paddingValues ->
                    Column(modifier = Modifier.padding(paddingValues)) {
                        if (editingLocked) {
                            EditingLockedBanner(
                                conflict = syncState.status == SyncStatus.CONFLICT,
                                enabled = !syncState.busy,
                                // In a conflict, downloading discards local edits — confirm first (matching
                                // the Settings sync UI). Otherwise the remote is simply ahead: download directly.
                                onDownload = {
                                    if (syncState.status == SyncStatus.CONFLICT) {
                                        showConflictDownloadConfirm = true
                                    } else {
                                        onReloadFromRemote()
                                    }
                                },
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Clears the FAB's pre-selection state on screens that don't track a viewed account.
                            val resetViewedIds: @Composable () -> Unit = {
                                LaunchedEffect(Unit) {
                                    currentlyViewedAccountId = null
                                    currentlyViewedCurrencyId = null
                                }
                            }
                            NavDisplay(
                                backStack = navigationHistory.backStack,
                                onBack = { navigationHistory.navigateBack() },
                                entryProvider =
                                    entryProvider {
                                        // Content keys deliberately ignore volatile params (scroll targets, tabs):
                                        // replaceCurrentScreen updates them in place and must not recreate the entry.
                                        entry<Screen.Accounts>(clazzContentKey = { "Accounts" }) { screen ->
                                            resetViewedIds()
                                            AccountsScreen(
                                                accountRepository = services.accounts.accountRepository,
                                                accountAttributeRepository = services.accounts.accountAttributeRepository,
                                                attributeTypeRepository = services.transactions.attributeTypeRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                transactionRepository = services.transactions.transactionRepository,
                                                personRepository = services.people.personRepository,
                                                personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                                maintenance = services.imports.maintenance,
                                                scrollToAccountId = screen.scrollToAccountId,
                                                onAccountClick = { account ->
                                                    // Replace current screen with one that remembers the clicked account
                                                    // so that navigating back will scroll to this account
                                                    navigationHistory.replaceCurrentScreen(Screen.Accounts(scrollToAccountId = account.id))
                                                    navigationHistory.navigateTo(Screen.AccountTransactions(account.id, account.name))
                                                },
                                                onAuditClick = { account ->
                                                    navigationHistory.navigateTo(Screen.AccountAuditHistory(account.id, account.name))
                                                },
                                            )
                                        }
                                        entry<Screen.Currencies> {
                                            resetViewedIds()
                                            AssetsScreen(
                                                currencyRepository = services.accounts.currencyRepository,
                                                cryptoRepository = services.accounts.cryptoRepository,
                                                onCurrencyAuditClick = { currency ->
                                                    navigationHistory.navigateTo(Screen.CurrencyAuditHistory(currency.id, currency.code))
                                                },
                                            )
                                        }
                                        entry<Screen.Categories> {
                                            resetViewedIds()
                                            CategoriesScreen(
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                onAuditClick = { category ->
                                                    navigationHistory.navigateTo(Screen.CategoryAuditHistory(category.id, category.name))
                                                },
                                            )
                                        }
                                        val peopleScreen: @Composable (scrollToPersonId: PersonId?) -> Unit = { scrollToPersonId ->
                                            resetViewedIds()
                                            PeopleScreen(
                                                personRepository = services.people.personRepository,
                                                personAttributeRepository = services.people.personAttributeRepository,
                                                personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                                attributeTypeRepository = services.transactions.attributeTypeRepository,
                                                scrollToPersonId = scrollToPersonId,
                                                onAuditClick = { person ->
                                                    navigationHistory.navigateTo(Screen.PersonAuditHistory(person.id, person.fullName))
                                                },
                                            )
                                        }
                                        entry<Screen.People> {
                                            peopleScreen(null)
                                        }
                                        // Shares the People content key so switching between the plain and
                                        // scroll-target variants keeps the same entry, as the old switcher did.
                                        entry<Screen.PeopleScroll>(clazzContentKey = { "People" }) { screen ->
                                            peopleScreen(screen.personId)
                                        }
                                        entry<Screen.Settings> {
                                            resetViewedIds()
                                            SettingsScreen(
                                                currencyRepository = services.accounts.currencyRepository,
                                                settingsRepository = services.settings.settingsRepository,
                                                maintenance = services.imports.maintenance,
                                                currentDatabaseLocation = databaseLocation,
                                                onRequestSwitchDatabase = onRequestSwitchDatabase,
                                                onReloadFromRemote = onReloadFromRemote,
                                                onShowDbSizeBreakdown = {
                                                    navigationHistory.navigateTo(Screen.DatabaseSizeBreakdown)
                                                },
                                                remoteController = remoteController,
                                                database = database,
                                                strategySyncController = strategySyncController,
                                                strategyLibrary = services.imports.strategyLibrary,
                                                cryptoCatalogRefresher = cryptoCatalogRefresher,
                                                appVersion = appVersion,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                personRepository = services.people.personRepository,
                                            )
                                        }
                                        entry<Screen.DatabaseSizeBreakdown> {
                                            resetViewedIds()
                                            if (database != null) {
                                                DatabaseSizeBreakdownScreen(
                                                    database = database,
                                                    onBack = { navigationHistory.navigateBack() },
                                                )
                                            } else {
                                                LaunchedEffect(Unit) { navigationHistory.navigateBack() }
                                            }
                                        }
                                        entry<Screen.AccountTransactions>(
                                            clazzContentKey = { "AccountTransactions:${it.accountId}" },
                                        ) { screen ->
                                            // Initialize currentlyViewedAccountId when first entering the screen
                                            LaunchedEffect(screen.accountId) {
                                                currentlyViewedAccountId = screen.accountId
                                            }
                                            AccountTransactionsScreen(
                                                accountId = currentlyViewedAccountId ?: screen.accountId,
                                                transactionRepository = services.transactions.transactionRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                accountAttributeRepository = services.accounts.accountAttributeRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                attributeTypeRepository = services.transactions.attributeTypeRepository,
                                                personRepository = services.people.personRepository,
                                                personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                                exchangeOrderRepository = services.transactions.exchangeOrderRepository,
                                                maintenance = services.imports.maintenance,
                                                onAccountIdChange = { accountId ->
                                                    currentlyViewedAccountId = accountId
                                                },
                                                onCurrencyIdChange = { currencyId ->
                                                    currentlyViewedCurrencyId = currencyId
                                                },
                                                onAccountClick = { accountId, accountName, currencyId, transferId ->
                                                    navigationHistory.navigateTo(
                                                        Screen.AccountTransactions(
                                                            accountId = accountId,
                                                            accountName = accountName,
                                                            selectedCurrencyId = currencyId,
                                                            scrollToTransferId = transferId,
                                                        ),
                                                    )
                                                },
                                                onAuditClick = { transferId ->
                                                    navigationHistory.navigateTo(Screen.AuditHistory(transferId))
                                                },
                                                // Clicking a fee badge jumps to the linked transfer, landing on its
                                                // (own) account scrolled to it.
                                                onFeeLinkClick = { transferId ->
                                                    navigateToTransferAccount(transferId, isPositiveAmount = false)
                                                },
                                                onOrdersClick = { accountId, accountName ->
                                                    navigationHistory.navigateTo(Screen.ExchangeOrders(accountId, accountName))
                                                },
                                                onOrderLinkClick = { orderId ->
                                                    navigationHistory.navigateTo(Screen.ExchangeOrderDetail(orderId))
                                                },
                                                scrollToTransferId = screen.scrollToTransferId,
                                                initialCurrencyId = screen.selectedCurrencyId,
                                                externalRefreshTrigger = transactionRefreshTrigger,
                                            )
                                        }
                                        entry<Screen.ExchangeOrders> { screen ->
                                            OrdersScreen(
                                                accountId = screen.accountId,
                                                exchangeOrderRepository = services.transactions.exchangeOrderRepository,
                                                onOrderClick = { orderId ->
                                                    navigationHistory.navigateTo(Screen.ExchangeOrderDetail(orderId))
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.ExchangeOrderDetail> { screen ->
                                            OrderDetailScreen(
                                                orderId = screen.orderId,
                                                exchangeOrderRepository = services.transactions.exchangeOrderRepository,
                                                onFillTradeClick = { fill ->
                                                    // A trade shares the transaction_id space, so the transactions
                                                    // screen's scroll-to accepts its id as a TransferId.
                                                    val account = accounts.find { it.id == fill.fromAccountId }
                                                    navigationHistory.navigateTo(
                                                        Screen.AccountTransactions(
                                                            accountId = fill.fromAccountId,
                                                            accountName = account?.name ?: fill.fromAccountId.toString(),
                                                            scrollToTransferId = TransferId(fill.id.id),
                                                        ),
                                                    )
                                                },
                                                onFillTradeAuditClick = { fill ->
                                                    // The trade audit screen resolves a trade by its shared
                                                    // transaction id, so its TransferId reaches its trade_audit trail.
                                                    navigationHistory.navigateTo(Screen.AuditHistory(TransferId(fill.id.id)))
                                                },
                                                onAuditClick = { orderId ->
                                                    navigationHistory.navigateTo(Screen.ExchangeOrderAuditHistory(orderId))
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.ExchangeOrderAuditHistory> { screen ->
                                            ExchangeOrderAuditScreen(
                                                orderId = screen.orderId,
                                                auditRepository = services.audit.auditRepository,
                                                onApiSourceClick = { sessionId, requestId, jsonPath ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ApiSessionTraffic(
                                                            sessionId = sessionId,
                                                            highlightRequestId = requestId,
                                                            highlightJsonPath = jsonPath,
                                                        ),
                                                    )
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.Imports>(clazzContentKey = { "Imports" }) { screen ->
                                            resetViewedIds()
                                            ImportsScreen(
                                                selectedTab = screen.tab,
                                                onTabSelected = { tab ->
                                                    navigationHistory.replaceCurrentScreen(Screen.Imports(tab))
                                                },
                                                importDirectoryRepository = services.imports.importDirectoryRepository,
                                                importTimelineRepository = services.imports.importTimelineRepository,
                                                importFileSourceFactory = importFileSourceFactory,
                                                driveFolderBrowser = driveFolderBrowser,
                                                csvImportRepository = services.imports.csvImportRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                accountMappingRepository = services.imports.accountMappingRepository,
                                                accountMappingExportService = services.imports.accountMappingExportService,
                                                appVersion = appVersion,
                                                qifImportRepository = services.imports.qifImportRepository,
                                                passThroughAccountRepository = services.imports.passThroughAccountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                settingsRepository = services.settings.settingsRepository,
                                                apiSessionRepository = services.imports.apiSessionRepository,
                                                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                                accountAttributeRepository = services.accounts.accountAttributeRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                cryptoRepository = services.accounts.cryptoRepository,
                                                transactionRepository = services.transactions.transactionRepository,
                                                transferRelationshipRepository = services.transactions.transferRelationshipRepository,
                                                transferSourceRepository = services.transactions.transferSourceRepository,
                                                tradeRepository = services.transactions.tradeRepository,
                                                maintenance = services.imports.maintenance,
                                                personRepository = services.people.personRepository,
                                                importEngine = services.transactions.importEngine,
                                                deviceId = services.deviceId,
                                                onCsvImportClick = { importId ->
                                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId))
                                                },
                                                onTimelineFileClick = { file ->
                                                    when (file.kind) {
                                                        TimelineSourceKind.CSV ->
                                                            navigationHistory.navigateTo(
                                                                Screen.CsvImportDetail(CsvImportId(Uuid.parse(file.fileId))),
                                                            )
                                                        TimelineSourceKind.QIF ->
                                                            navigationHistory.navigateTo(
                                                                Screen.QifImportDetail(QifImportId(Uuid.parse(file.fileId))),
                                                            )
                                                        TimelineSourceKind.API ->
                                                            navigationHistory.navigateTo(
                                                                Screen.ApiSessionTraffic(ApiSessionId(file.fileId.toLong())),
                                                            )
                                                        TimelineSourceKind.MANUAL -> {}
                                                    }
                                                },
                                                onCsvStrategiesClick = {
                                                    navigationHistory.navigateTo(Screen.CsvStrategies)
                                                },
                                                onQifImportClick = { importId ->
                                                    navigationHistory.navigateTo(Screen.QifImportDetail(importId))
                                                },
                                                onAddCredentialClick = {
                                                    navigationHistory.navigateTo(Screen.ConnectApi)
                                                },
                                                onApiStrategiesClick = {
                                                    navigationHistory.navigateTo(Screen.ApiStrategies)
                                                },
                                                onSessionClick = { session ->
                                                    navigationHistory.navigateTo(Screen.ApiSessionTraffic(session.id))
                                                },
                                                onImportDirectoryAuditClick = { directory ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ImportDirectoryAuditHistory(directory.id, directory.name),
                                                    )
                                                },
                                                onTransactionsImported = {
                                                    transactionRefreshTrigger++
                                                },
                                                onBrowsePassThroughCatalog = {
                                                    navigationHistory.navigateTo(Screen.StrategyCatalog(StrategyKind.PASS_THROUGH))
                                                },
                                            )
                                        }
                                        entry<Screen.StrategyCatalog> { screen ->
                                            resetViewedIds()
                                            if (strategyCatalogController != null) {
                                                StrategyCatalogScreen(
                                                    controller = strategyCatalogController,
                                                    library = services.imports.strategyLibrary,
                                                    appVersion = appVersion,
                                                    accountRepository = services.accounts.accountRepository,
                                                    categoryRepository = services.accounts.categoryRepository,
                                                    currencyRepository = services.accounts.currencyRepository,
                                                    personRepository = services.people.personRepository,
                                                    initialKindFilter = screen.kindFilter,
                                                    onBack = { navigationHistory.navigateBack() },
                                                )
                                            } else {
                                                LaunchedEffect(Unit) { navigationHistory.navigateBack() }
                                            }
                                        }
                                        entry<Screen.ApiStrategies> {
                                            resetViewedIds()
                                            ApiStrategiesScreen(
                                                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                                onBrowseCatalog = {
                                                    navigationHistory.navigateTo(Screen.StrategyCatalog(StrategyKind.API))
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                                onCreateStrategy = {
                                                    navigationHistory.navigateTo(Screen.ApiStrategyEditor())
                                                },
                                                onEditStrategy = { strategyId ->
                                                    navigationHistory.navigateTo(Screen.ApiStrategyEditor(strategyId))
                                                },
                                                onAuditHistoryClick = { strategy ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ApiStrategyAuditHistory(strategy.id, strategy.name),
                                                    )
                                                },
                                            )
                                        }
                                        entry<Screen.ApiStrategyEditor> { screen ->
                                            resetViewedIds()
                                            ApiStrategyEditorScreen(
                                                strategyId = screen.strategyId,
                                                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                                apiSessionRepository = services.imports.apiSessionRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.CsvImportDetail>(
                                            clazzContentKey = { "CsvImportDetail:${it.importId}" },
                                        ) { screen ->
                                            CsvImportDetailScreen(
                                                importId = screen.importId,
                                                scrollToRowIndex = screen.scrollToRowIndex,
                                                csvImportRepository = services.imports.csvImportRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                accountMappingRepository = services.imports.accountMappingRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                personRepository = services.people.personRepository,
                                                passThroughAccountRepository = services.imports.passThroughAccountRepository,
                                                cryptoRepository = services.accounts.cryptoRepository,
                                                maintenance = services.imports.maintenance,
                                                transactionRepository = services.transactions.transactionRepository,
                                                transferSourceRepository = services.transactions.transferSourceRepository,
                                                transferRelationshipRepository = services.transactions.transferRelationshipRepository,
                                                tradeRepository = services.transactions.tradeRepository,
                                                importEngine = services.transactions.importEngine,
                                                onBack = { navigationHistory.navigateBack() },
                                                onDeleted = { navigationHistory.navigateTo(Screen.Imports(ImportTab.CSV)) },
                                                onCreateStrategy = { importId ->
                                                    navigationHistory.navigateTo(Screen.CsvStrategyEditor(importId))
                                                },
                                                onCsvSourceClick = { importId, rowIndex ->
                                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId, rowIndex))
                                                },
                                                onTransferClick = ::navigateToTransferAccount,
                                            )
                                        }
                                        entry<Screen.QifImportDetail>(
                                            clazzContentKey = { "QifImportDetail:${it.importId}" },
                                        ) { screen ->
                                            QifImportDetailScreen(
                                                importId = screen.importId,
                                                scrollToRecordIndex = screen.scrollToRecordIndex,
                                                qifImportRepository = services.imports.qifImportRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                accountMappingRepository = services.imports.accountMappingRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                personRepository = services.people.personRepository,
                                                settingsRepository = services.settings.settingsRepository,
                                                transactionRepository = services.transactions.transactionRepository,
                                                transferSourceRepository = services.transactions.transferSourceRepository,
                                                maintenance = services.imports.maintenance,
                                                importEngine = services.transactions.importEngine,
                                                onBack = { navigationHistory.navigateBack() },
                                                onCreateStrategy = { qifImportId ->
                                                    navigationHistory.navigateTo(Screen.QifStrategyEditor(qifImportId))
                                                },
                                                onDeleted = { navigationHistory.navigateTo(Screen.Imports(ImportTab.QIF)) },
                                                onTransferClick = ::navigateToTransferAccount,
                                            )
                                        }
                                        entry<Screen.CsvStrategies> {
                                            resetViewedIds()
                                            CsvStrategiesScreen(
                                                onBrowseCatalog = {
                                                    navigationHistory.navigateTo(Screen.StrategyCatalog(StrategyKind.CSV))
                                                },
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                csvImportRepository = services.imports.csvImportRepository,
                                                qifImportRepository = services.imports.qifImportRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                personRepository = services.people.personRepository,
                                                csvStrategyImportExport = services.imports.csvStrategyImportExport,
                                                strategyLibrary = services.imports.strategyLibrary,
                                                appVersion = appVersion,
                                                onBack = { navigationHistory.navigateBack() },
                                                onEditStrategy = { strategyId, importId ->
                                                    navigationHistory.navigateTo(Screen.CsvStrategyEditor(importId, strategyId))
                                                },
                                                onEditQifStrategy = { strategyId, qifImportId ->
                                                    navigationHistory.navigateTo(Screen.QifStrategyEditor(qifImportId, strategyId))
                                                },
                                                onAuditHistoryClick = { strategy ->
                                                    navigationHistory.navigateTo(
                                                        Screen.CsvStrategyAuditHistory(strategy.id, strategy.name),
                                                    )
                                                },
                                            )
                                        }
                                        entry<Screen.CsvStrategyEditor> { screen ->
                                            CsvStrategyEditorScreen(
                                                csvImportId = screen.csvImportId,
                                                strategyId = screen.strategyId,
                                                csvImportRepository = services.imports.csvImportRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                accountMappingRepository = services.imports.accountMappingRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                attributeTypeRepository = services.transactions.attributeTypeRepository,
                                                personRepository = services.people.personRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.QifStrategyEditor> { screen ->
                                            QifStrategyEditorScreen(
                                                qifImportId = screen.qifImportId,
                                                strategyId = screen.strategyId,
                                                qifImportRepository = services.imports.qifImportRepository,
                                                csvImportRepository = services.imports.csvImportRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                accountMappingRepository = services.imports.accountMappingRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                attributeTypeRepository = services.transactions.attributeTypeRepository,
                                                personRepository = services.people.personRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.AuditHistory> { screen ->
                                            TransactionAuditScreen(
                                                transferId = screen.transferId,
                                                auditRepository = services.audit.auditRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                transactionRepository = services.transactions.transactionRepository,
                                                currentDeviceId = services.deviceId,
                                                onCsvSourceClick = { importId, rowIndex ->
                                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId, rowIndex))
                                                },
                                                onQifSourceClick = { importId, recordIndex ->
                                                    navigationHistory.navigateTo(Screen.QifImportDetail(importId, recordIndex))
                                                },
                                                onApiSourceClick = { sessionId, requestId, jsonPath ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ApiSessionTraffic(
                                                            sessionId = sessionId,
                                                            highlightRequestId = requestId,
                                                            highlightJsonPath = jsonPath,
                                                        ),
                                                    )
                                                },
                                                onAccountClick = { accountId ->
                                                    val account = accounts.find { it.id == accountId }
                                                    navigationHistory.navigateTo(
                                                        Screen.AccountTransactions(
                                                            accountId = accountId,
                                                            accountName = account?.name ?: "#${accountId.id}",
                                                        ),
                                                    )
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.AccountAuditHistory> { screen ->
                                            AccountAuditScreen(
                                                accountId = screen.accountId,
                                                auditRepository = services.audit.auditRepository,
                                                accountRepository = services.accounts.accountRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                maintenance = services.imports.maintenance,
                                                onApiSourceClick = { sessionId, requestId, jsonPath ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ApiSessionTraffic(
                                                            sessionId = sessionId,
                                                            highlightRequestId = requestId,
                                                            highlightJsonPath = jsonPath,
                                                        ),
                                                    )
                                                },
                                                onCsvSourceClick = { importId, rowIndex ->
                                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId, rowIndex))
                                                },
                                                onQifSourceClick = { importId, recordIndex ->
                                                    navigationHistory.navigateTo(Screen.QifImportDetail(importId, recordIndex))
                                                },
                                                onOwnerClick = { personId ->
                                                    navigationHistory.navigateTo(
                                                        Screen.PeopleScroll(
                                                            personId = personId,
                                                        ),
                                                    )
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.PersonAuditHistory> { screen ->
                                            PersonAuditScreen(
                                                personId = screen.personId,
                                                auditRepository = services.audit.auditRepository,
                                                personRepository = services.people.personRepository,
                                                onApiSourceClick = { sessionId, requestId, jsonPath ->
                                                    navigationHistory.navigateTo(
                                                        Screen.ApiSessionTraffic(
                                                            sessionId = sessionId,
                                                            highlightRequestId = requestId,
                                                            highlightJsonPath = jsonPath,
                                                        ),
                                                    )
                                                },
                                                onCsvSourceClick = { importId, rowIndex ->
                                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId, rowIndex))
                                                },
                                                onQifSourceClick = { importId, recordIndex ->
                                                    navigationHistory.navigateTo(Screen.QifImportDetail(importId, recordIndex))
                                                },
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.CurrencyAuditHistory> { screen ->
                                            CurrencyAuditScreen(
                                                currencyId = screen.currencyId,
                                                auditRepository = services.audit.auditRepository,
                                                currencyRepository = services.accounts.currencyRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.CategoryAuditHistory> { screen ->
                                            CategoryAuditScreen(
                                                categoryId = screen.categoryId,
                                                auditRepository = services.audit.auditRepository,
                                                categoryRepository = services.accounts.categoryRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.ApiStrategyAuditHistory> { screen ->
                                            ApiImportStrategyAuditScreen(
                                                strategyId = screen.strategyId,
                                                auditRepository = services.audit.auditRepository,
                                                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.CsvStrategyAuditHistory> { screen ->
                                            CsvImportStrategyAuditScreen(
                                                strategyId = screen.strategyId,
                                                auditRepository = services.audit.auditRepository,
                                                csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.ImportDirectoryAuditHistory> { screen ->
                                            ImportDirectoryAuditScreen(
                                                directoryId = screen.directoryId,
                                                auditRepository = services.audit.auditRepository,
                                                importDirectoryRepository = services.imports.importDirectoryRepository,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                        entry<Screen.ConnectApi> {
                                            resetViewedIds()
                                            ApiConnectScreen(
                                                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                                onCredentialSaved = {
                                                    navigationHistory.navigateBack()
                                                },
                                            )
                                        }

                                        entry<Screen.ApiSessionTraffic>(
                                            clazzContentKey = { "ApiSessionTraffic:${it.sessionId}" },
                                        ) { screen ->
                                            resetViewedIds()
                                            ApiSessionTrafficScreen(
                                                apiSessionRepository = services.imports.apiSessionRepository,
                                                sessionId = screen.sessionId,
                                                highlightRequestId = screen.highlightRequestId,
                                                highlightJsonPath = screen.highlightJsonPath,
                                                onBack = { navigationHistory.navigateBack() },
                                            )
                                        }
                                    },
                            )
                            BackgroundTaskPanel(
                                manager = backgroundTaskManager,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp),
                            )
                        }
                    }
                }

                if (defaultCurrencyLoaded && defaultCurrencyId == null) {
                    DefaultCurrencyInitDialog(
                        currencyRepository = services.accounts.currencyRepository,
                    )
                }

                if (showTransactionDialog && !editingLocked) {
                    TransactionEditDialog(
                        accountRepository = services.accounts.accountRepository,
                        categoryRepository = services.accounts.categoryRepository,
                        currencyRepository = services.accounts.currencyRepository,
                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                        personRepository = services.people.personRepository,
                        maintenance = services.imports.maintenance,
                        preSelectedSourceAccountId = preSelectedAccountId,
                        preSelectedCurrencyId = preSelectedCurrencyId,
                        onDismiss = {
                            showTransactionDialog = false
                            preSelectedAccountId = null
                            preSelectedCurrencyId = null
                        },
                        onSaved = {
                            transactionRefreshTrigger++
                        },
                    )
                }

                if (showTradeDialog && !editingLocked) {
                    TradeEntryDialog(
                        accountRepository = services.accounts.accountRepository,
                        categoryRepository = services.accounts.categoryRepository,
                        personRepository = services.people.personRepository,
                        currencyRepository = services.accounts.currencyRepository,
                        cryptoRepository = services.accounts.cryptoRepository,
                        maintenance = services.imports.maintenance,
                        onDismiss = { showTradeDialog = false },
                        onSaved = { transactionRefreshTrigger++ },
                    )
                }

                if (showConflictDownloadConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConflictDownloadConfirm = false },
                        title = { Text("Discard local changes?") },
                        text = {
                            Text(
                                "Another device changed this database since your last sync. Downloading now will " +
                                    "discard your local unsynced changes. This can't be undone.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showConflictDownloadConfirm = false
                                onReloadFromRemote()
                            }) { Text("Discard local") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConflictDownloadConfirm = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

/**
 * App-wide banner shown when the remote copy is ahead of this device, so editing is locked until the
 * user downloads. On a [conflict] (both sides changed) downloading discards local changes, so the copy
 * is worded as a warning.
 */
@Composable
private fun EditingLockedBanner(
    conflict: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                text =
                    if (conflict) {
                        "Editing is locked: this database changed on another device and here. " +
                            "Download to continue (your local changes will be discarded)."
                    } else {
                        "Editing is locked: another device updated this database. Download to continue."
                    },
            )
            Button(onClick = onDownload, enabled = enabled) { Text("Download") }
        }
    }
}
