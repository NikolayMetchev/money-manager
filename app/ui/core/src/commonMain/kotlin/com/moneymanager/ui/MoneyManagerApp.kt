@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.TransferId
import com.moneymanager.ui.background.BackgroundTaskPanel
import com.moneymanager.ui.background.LocalBackgroundTaskManager
import com.moneymanager.ui.background.rememberBackgroundTaskManager
import com.moneymanager.ui.components.DefaultCurrencyInitDialog
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.navigation.NavigationHistory
import com.moneymanager.ui.navigation.PlatformBackHandler
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.navigation.mouseButtonNavigation
import com.moneymanager.ui.screens.AccountAuditScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.ApiConnectScreen
import com.moneymanager.ui.screens.ApiSessionTrafficScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CategoryAuditScreen
import com.moneymanager.ui.screens.CsvImportDetailScreen
import com.moneymanager.ui.screens.CurrenciesScreen
import com.moneymanager.ui.screens.CurrencyAuditScreen
import com.moneymanager.ui.screens.ImportsScreen
import com.moneymanager.ui.screens.PeopleScreen
import com.moneymanager.ui.screens.PersonAuditScreen
import com.moneymanager.ui.screens.QifImportDetailScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.apistrategy.ApiImportStrategyAuditScreen
import com.moneymanager.ui.screens.apistrategy.ApiStrategiesScreen
import com.moneymanager.ui.screens.apistrategy.editor.ApiStrategyEditorScreen
import com.moneymanager.ui.screens.csvstrategy.CsvImportStrategyAuditScreen
import com.moneymanager.ui.screens.csvstrategy.CsvStrategiesScreen
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorScreen
import com.moneymanager.ui.screens.qif.QifStrategyEditorScreen
import com.moneymanager.ui.screens.transactions.AccountTransactionsScreen
import com.moneymanager.ui.screens.transactions.TransactionAuditScreen
import com.moneymanager.ui.screens.transactions.TransactionEditDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    appVersion: AppVersion,
    databaseLocation: DbLocation,
    services: AppServices,
) {
    ProvideSchemaAwareScope {
        val scope = rememberSchemaAwareCoroutineScope()
        val backgroundTaskManager = rememberBackgroundTaskManager(scope)
        val navigationHistory = remember { NavigationHistory(Screen.Accounts()) }
        val currentScreen = navigationHistory.currentScreen
        var showTransactionDialog by remember { mutableStateOf(false) }
        var preSelectedAccountId by remember { mutableStateOf<AccountId?>(null) }
        var currentlyViewedAccountId by remember { mutableStateOf<AccountId?>(null) }
        var preSelectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
        var currentlyViewedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
        var transactionRefreshTrigger by remember { mutableStateOf(0) }

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
            ) {
                // Handle system back button (Android) when there's navigation history
                PlatformBackHandler(enabled = navigationHistory.canGoBack) {
                    navigationHistory.navigateBack()
                }

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
                                selected = currentScreen is Screen.Accounts || currentScreen is Screen.AccountTransactions,
                                onClick = { navigationHistory.navigateTo(Screen.Accounts()) },
                            )
                            NavigationBarItem(
                                icon = { Text("\uD83D\uDCB1") },
                                label = { Text("Currencies") },
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
                                                else -> ImportTab.CSV
                                            },
                                        ),
                                    )
                                },
                            )
                            NavigationBarItem(
                                icon = { Text("\u2699\uFE0F") },
                                label = { Text("Settings") },
                                selected = currentScreen is Screen.Settings,
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
                        if (showTransactionFab) {
                            FloatingActionButton(
                                onClick = {
                                    preSelectedAccountId = currentlyViewedAccountId
                                    preSelectedCurrencyId = currentlyViewedCurrencyId ?: defaultCurrencyId
                                    showTransactionDialog = true
                                },
                            ) {
                                Text("+", style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                    },
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues)) {
                        currentScreen.let { screen ->
                            when (screen) {
                                is Screen.Accounts -> {
                                    // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
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
                                is Screen.Currencies -> {
                                    // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    CurrenciesScreen(
                                        currencyRepository = services.accounts.currencyRepository,
                                        onAuditClick = { currency ->
                                            navigationHistory.navigateTo(Screen.CurrencyAuditHistory(currency.id, currency.code))
                                        },
                                    )
                                }
                                is Screen.Categories -> {
                                    // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    CategoriesScreen(
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        onAuditClick = { category ->
                                            navigationHistory.navigateTo(Screen.CategoryAuditHistory(category.id, category.name))
                                        },
                                    )
                                }
                                is Screen.People,
                                is Screen.PeopleScroll,
                                -> {
                                    // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    PeopleScreen(
                                        personRepository = services.people.personRepository,
                                        personAttributeRepository = services.people.personAttributeRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        scrollToPersonId = (screen as? Screen.PeopleScroll)?.personId,
                                        onAuditClick = { person ->
                                            navigationHistory.navigateTo(Screen.PersonAuditHistory(person.id, person.fullName))
                                        },
                                    )
                                }
                                is Screen.Settings -> {
                                    // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    SettingsScreen(
                                        currencyRepository = services.accounts.currencyRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        transactionRepository = services.transactions.transactionRepository,
                                        settingsRepository = services.settings.settingsRepository,
                                        maintenance = services.imports.maintenance,
                                    )
                                }
                                is Screen.AccountTransactions -> {
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
                                        scrollToTransferId = screen.scrollToTransferId,
                                        initialCurrencyId = screen.selectedCurrencyId,
                                        externalRefreshTrigger = transactionRefreshTrigger,
                                    )
                                }
                                is Screen.Imports -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    ImportsScreen(
                                        selectedTab = screen.tab,
                                        onTabSelected = { tab ->
                                            navigationHistory.replaceCurrentScreen(Screen.Imports(tab))
                                        },
                                        csvImportRepository = services.imports.csvImportRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        qifImportRepository = services.imports.qifImportRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        settingsRepository = services.settings.settingsRepository,
                                        apiSessionRepository = services.imports.apiSessionRepository,
                                        apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        accountAttributeRepository = services.accounts.accountAttributeRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        transactionRepository = services.transactions.transactionRepository,
                                        maintenance = services.imports.maintenance,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        importEngine = services.transactions.importEngine,
                                        deviceId = services.deviceId,
                                        onCsvImportClick = { importId ->
                                            navigationHistory.navigateTo(Screen.CsvImportDetail(importId))
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
                                        onTransactionsImported = {
                                            transactionRefreshTrigger++
                                        },
                                    )
                                }
                                is Screen.ApiStrategies -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    ApiStrategiesScreen(
                                        apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
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
                                is Screen.ApiStrategyEditor -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    ApiStrategyEditorScreen(
                                        strategyId = screen.strategyId,
                                        apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                        apiSessionRepository = services.imports.apiSessionRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.CsvImportDetail -> {
                                    CsvImportDetailScreen(
                                        importId = screen.importId,
                                        scrollToRowIndex = screen.scrollToRowIndex,
                                        csvImportRepository = services.imports.csvImportRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        maintenance = services.imports.maintenance,
                                        transferSourceRepository = services.transactions.transferSourceRepository,
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
                                is Screen.QifImportDetail -> {
                                    QifImportDetailScreen(
                                        importId = screen.importId,
                                        scrollToRecordIndex = screen.scrollToRecordIndex,
                                        qifImportRepository = services.imports.qifImportRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        settingsRepository = services.settings.settingsRepository,
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
                                is Screen.CsvStrategies -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    CsvStrategiesScreen(
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvImportRepository = services.imports.csvImportRepository,
                                        qifImportRepository = services.imports.qifImportRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        csvStrategyImportExport = services.imports.csvStrategyImportExport,
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
                                is Screen.CsvStrategyEditor -> {
                                    CsvStrategyEditorScreen(
                                        csvImportId = screen.csvImportId,
                                        strategyId = screen.strategyId,
                                        csvImportRepository = services.imports.csvImportRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.QifStrategyEditor -> {
                                    QifStrategyEditorScreen(
                                        qifImportId = screen.qifImportId,
                                        strategyId = screen.strategyId,
                                        qifImportRepository = services.imports.qifImportRepository,
                                        csvImportRepository = services.imports.csvImportRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        csvAccountMappingRepository = services.imports.csvAccountMappingRepository,
                                        accountRepository = services.accounts.accountRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                                        personRepository = services.people.personRepository,
                                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.AuditHistory -> {
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
                                is Screen.AccountAuditHistory -> {
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
                                is Screen.PersonAuditHistory -> {
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
                                is Screen.CurrencyAuditHistory -> {
                                    CurrencyAuditScreen(
                                        currencyId = screen.currencyId,
                                        auditRepository = services.audit.auditRepository,
                                        currencyRepository = services.accounts.currencyRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.CategoryAuditHistory -> {
                                    CategoryAuditScreen(
                                        categoryId = screen.categoryId,
                                        auditRepository = services.audit.auditRepository,
                                        categoryRepository = services.accounts.categoryRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.ApiStrategyAuditHistory -> {
                                    ApiImportStrategyAuditScreen(
                                        strategyId = screen.strategyId,
                                        auditRepository = services.audit.auditRepository,
                                        apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.CsvStrategyAuditHistory -> {
                                    CsvImportStrategyAuditScreen(
                                        strategyId = screen.strategyId,
                                        auditRepository = services.audit.auditRepository,
                                        csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                                is Screen.ConnectApi -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    ApiConnectScreen(
                                        apiSessionRepository = services.imports.apiSessionRepository,
                                        apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                                        onCredentialSaved = {
                                            navigationHistory.navigateBack()
                                        },
                                    )
                                }

                                is Screen.ApiSessionTraffic -> {
                                    LaunchedEffect(Unit) {
                                        currentlyViewedAccountId = null
                                        currentlyViewedCurrencyId = null
                                    }
                                    ApiSessionTrafficScreen(
                                        apiSessionRepository = services.imports.apiSessionRepository,
                                        sessionId = screen.sessionId,
                                        highlightRequestId = screen.highlightRequestId,
                                        highlightJsonPath = screen.highlightJsonPath,
                                        onBack = { navigationHistory.navigateBack() },
                                    )
                                }
                            }
                        }
                        BackgroundTaskPanel(
                            manager = backgroundTaskManager,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                        )
                    }
                }

                if (defaultCurrencyLoaded && defaultCurrencyId == null) {
                    DefaultCurrencyInitDialog(
                        currencyRepository = services.accounts.currencyRepository,
                        settingsRepository = services.settings.settingsRepository,
                    )
                }

                if (showTransactionDialog) {
                    TransactionEditDialog(
                        transactionRepository = services.transactions.transactionRepository,
                        accountRepository = services.accounts.accountRepository,
                        categoryRepository = services.accounts.categoryRepository,
                        currencyRepository = services.accounts.currencyRepository,
                        attributeTypeRepository = services.transactions.attributeTypeRepository,
                        personRepository = services.people.personRepository,
                        personAccountOwnershipRepository = services.people.personAccountOwnershipRepository,
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
            }
        }
    }
}
