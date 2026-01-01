@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountTransactionsScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CsvImportDetailScreen
import com.moneymanager.ui.screens.CsvImportsScreen
import com.moneymanager.ui.screens.CurrenciesScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.TransactionEntryDialog
import com.moneymanager.ui.screens.csvstrategy.CsvStrategiesScreen
import kotlinx.coroutines.launch

private data class LoadedRepositories(
    val accountRepository: AccountRepository,
    val attributeTypeRepository: AttributeTypeRepository,
    val auditRepository: AuditRepository,
    val categoryRepository: CategoryRepository,
    val csvImportRepository: CsvImportRepository,
    val csvImportStrategyRepository: CsvImportStrategyRepository,
    val currencyRepository: CurrencyRepository,
    val deviceRepository: DeviceRepository,
    val maintenanceService: DatabaseMaintenanceService,
    val transactionRepository: TransactionRepository,
    val transferAttributeRepository: TransferAttributeRepository,
    val transferSourceRepository: TransferSourceRepository,
    val transferSourceQueries: TransferSourceQueries,
    val deviceId: DeviceId,
)

private sealed class AppDatabaseState {
    data object Loading : AppDatabaseState()

    data class Loaded(
        val location: DbLocation,
        val database: MoneyManagerDatabaseWrapper,
        val repositories: LoadedRepositories,
    ) : AppDatabaseState()

    data class Error(val location: DbLocation, val error: Throwable) : AppDatabaseState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    createRepositories: (MoneyManagerDatabaseWrapper, RepositoryCallback) -> Unit,
    onLog: (String, Throwable?) -> Unit = { _, _ -> },
) {
    // Provide schema-aware coroutine scope to all child composables
    ProvideSchemaAwareScope {
        MoneyManagerAppImpl(databaseManager, appVersion, createRepositories, onLog)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoneyManagerAppImpl(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    createRepositories: (MoneyManagerDatabaseWrapper, RepositoryCallback) -> Unit,
    onLog: (String, Throwable?) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading) }

    // Open database on first composition
    LaunchedEffect(Unit) {
        try {
            val location = databaseManager.getDefaultLocation()
            onLog("Opening database at: $location", null)
            val database = databaseManager.openDatabase(location)

            // Create repositories via callback
            createRepositories(
                database,
                object : RepositoryCallback {
                    override fun onRepositoriesReady(
                        accountRepository: AccountRepository,
                        attributeTypeRepository: AttributeTypeRepository,
                        auditRepository: AuditRepository,
                        categoryRepository: CategoryRepository,
                        csvImportRepository: CsvImportRepository,
                        csvImportStrategyRepository: CsvImportStrategyRepository,
                        currencyRepository: CurrencyRepository,
                        deviceRepository: DeviceRepository,
                        maintenanceService: DatabaseMaintenanceService,
                        transactionRepository: TransactionRepository,
                        transferAttributeRepository: TransferAttributeRepository,
                        transferSourceRepository: TransferSourceRepository,
                        transferSourceQueries: TransferSourceQueries,
                        deviceId: DeviceId,
                    ) {
                        databaseState =
                            AppDatabaseState.Loaded(
                                location = location,
                                database = database,
                                repositories =
                                    LoadedRepositories(
                                        accountRepository = accountRepository,
                                        attributeTypeRepository = attributeTypeRepository,
                                        auditRepository = auditRepository,
                                        categoryRepository = categoryRepository,
                                        csvImportRepository = csvImportRepository,
                                        csvImportStrategyRepository = csvImportStrategyRepository,
                                        currencyRepository = currencyRepository,
                                        deviceRepository = deviceRepository,
                                        maintenanceService = maintenanceService,
                                        transactionRepository = transactionRepository,
                                        transferAttributeRepository = transferAttributeRepository,
                                        transferSourceRepository = transferSourceRepository,
                                        transferSourceQueries = transferSourceQueries,
                                        deviceId = deviceId,
                                    ),
                            )
                    }
                },
            )
            onLog("Database opened successfully", null)
        } catch (expected: Exception) {
            onLog("Failed to open database: ${expected.message}", expected)
            databaseState = AppDatabaseState.Error(databaseManager.getDefaultLocation(), expected)
        }
    }

    // Observe global schema error state from Flow collection error handlers
    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()

    // Determine which error to show - prioritize global errors (runtime) over local (startup)
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError?.let { info ->
            // For global errors, get the current database location from state if available
            val location =
                (databaseState as? AppDatabaseState.Loaded)?.location
                    ?: databaseManager.getDefaultLocation()
            location to info.error
        } ?: (databaseState as? AppDatabaseState.Error)?.let { it.location to it.error }

    // Show main app once database is loaded
    when (val state = databaseState) {
        is AppDatabaseState.Loaded -> {
            val repos = state.repositories
            MoneyManagerAppContent(
                appVersion = appVersion,
                databaseLocation = state.location,
                accountRepository = repos.accountRepository,
                attributeTypeRepository = repos.attributeTypeRepository,
                auditRepository = repos.auditRepository,
                categoryRepository = repos.categoryRepository,
                csvImportRepository = repos.csvImportRepository,
                csvImportStrategyRepository = repos.csvImportStrategyRepository,
                currencyRepository = repos.currencyRepository,
                deviceRepository = repos.deviceRepository,
                maintenanceService = repos.maintenanceService,
                transactionRepository = repos.transactionRepository,
                transferAttributeRepository = repos.transferAttributeRepository,
                transferSourceRepository = repos.transferSourceRepository,
                transferSourceQueries = repos.transferSourceQueries,
                deviceId = repos.deviceId,
            )
        }
        is AppDatabaseState.Loading -> {
            // Loading...
        }
        is AppDatabaseState.Error -> {
            // Error dialog is shown below
        }
    }

    // Show database schema error dialog if there's an error (rendered AFTER content to appear on top)
    effectiveSchemaError?.let { (location, error) ->
        DatabaseSchemaErrorDialog(
            databaseLocation = location.toString(),
            error = error,
            onBackupAndCreateNew = {
                scope.launch {
                    try {
                        onLog("Backing up database and creating new one...", null)
                        val backupLocation = databaseManager.backupDatabase(location)
                        onLog("Database backed up to: $backupLocation", null)

                        val database = databaseManager.openDatabase(location)
                        createRepositories(
                            database,
                            object : RepositoryCallback {
                                override fun onRepositoriesReady(
                                    accountRepository: AccountRepository,
                                    attributeTypeRepository: AttributeTypeRepository,
                                    auditRepository: AuditRepository,
                                    categoryRepository: CategoryRepository,
                                    csvImportRepository: CsvImportRepository,
                                    csvImportStrategyRepository: CsvImportStrategyRepository,
                                    currencyRepository: CurrencyRepository,
                                    deviceRepository: DeviceRepository,
                                    maintenanceService: DatabaseMaintenanceService,
                                    transactionRepository: TransactionRepository,
                                    transferAttributeRepository: TransferAttributeRepository,
                                    transferSourceRepository: TransferSourceRepository,
                                    transferSourceQueries: TransferSourceQueries,
                                    deviceId: DeviceId,
                                ) {
                                    databaseState =
                                        AppDatabaseState.Loaded(
                                            location = location,
                                            database = database,
                                            repositories =
                                                LoadedRepositories(
                                                    accountRepository = accountRepository,
                                                    attributeTypeRepository = attributeTypeRepository,
                                                    auditRepository = auditRepository,
                                                    categoryRepository = categoryRepository,
                                                    csvImportRepository = csvImportRepository,
                                                    csvImportStrategyRepository = csvImportStrategyRepository,
                                                    currencyRepository = currencyRepository,
                                                    deviceRepository = deviceRepository,
                                                    maintenanceService = maintenanceService,
                                                    transactionRepository = transactionRepository,
                                                    transferAttributeRepository = transferAttributeRepository,
                                                    transferSourceRepository = transferSourceRepository,
                                                    transferSourceQueries = transferSourceQueries,
                                                    deviceId = deviceId,
                                                ),
                                        )
                                }
                            },
                        )
                        GlobalSchemaErrorState.clearError()
                        onLog("New database created successfully", null)
                    } catch (expected: Exception) {
                        onLog("Failed to backup and create new database: ${expected.message}", expected)
                        databaseState = AppDatabaseState.Error(location, expected)
                    }
                }
            },
            onDeleteAndCreateNew = {
                scope.launch {
                    try {
                        onLog("Deleting database and creating new one...", null)
                        databaseManager.deleteDatabase(location)
                        onLog("Database deleted", null)

                        val database = databaseManager.openDatabase(location)
                        createRepositories(
                            database,
                            object : RepositoryCallback {
                                override fun onRepositoriesReady(
                                    accountRepository: AccountRepository,
                                    attributeTypeRepository: AttributeTypeRepository,
                                    auditRepository: AuditRepository,
                                    categoryRepository: CategoryRepository,
                                    csvImportRepository: CsvImportRepository,
                                    csvImportStrategyRepository: CsvImportStrategyRepository,
                                    currencyRepository: CurrencyRepository,
                                    deviceRepository: DeviceRepository,
                                    maintenanceService: DatabaseMaintenanceService,
                                    transactionRepository: TransactionRepository,
                                    transferAttributeRepository: TransferAttributeRepository,
                                    transferSourceRepository: TransferSourceRepository,
                                    transferSourceQueries: TransferSourceQueries,
                                    deviceId: DeviceId,
                                ) {
                                    databaseState =
                                        AppDatabaseState.Loaded(
                                            location = location,
                                            database = database,
                                            repositories =
                                                LoadedRepositories(
                                                    accountRepository = accountRepository,
                                                    attributeTypeRepository = attributeTypeRepository,
                                                    auditRepository = auditRepository,
                                                    categoryRepository = categoryRepository,
                                                    csvImportRepository = csvImportRepository,
                                                    csvImportStrategyRepository = csvImportStrategyRepository,
                                                    currencyRepository = currencyRepository,
                                                    deviceRepository = deviceRepository,
                                                    maintenanceService = maintenanceService,
                                                    transactionRepository = transactionRepository,
                                                    transferAttributeRepository = transferAttributeRepository,
                                                    transferSourceRepository = transferSourceRepository,
                                                    transferSourceQueries = transferSourceQueries,
                                                    deviceId = deviceId,
                                                ),
                                        )
                                }
                            },
                        )
                        GlobalSchemaErrorState.clearError()
                        onLog("New database created successfully", null)
                    } catch (expected: Exception) {
                        onLog("Failed to delete and create new database: ${expected.message}", expected)
                        databaseState = AppDatabaseState.Error(location, expected)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoneyManagerAppContent(
    appVersion: AppVersion,
    databaseLocation: DbLocation,
    accountRepository: AccountRepository,
    attributeTypeRepository: AttributeTypeRepository,
    auditRepository: AuditRepository,
    categoryRepository: CategoryRepository,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    currencyRepository: CurrencyRepository,
    deviceRepository: DeviceRepository,
    maintenanceService: DatabaseMaintenanceService,
    transactionRepository: TransactionRepository,
    transferAttributeRepository: TransferAttributeRepository,
    transferSourceRepository: TransferSourceRepository,
    transferSourceQueries: TransferSourceQueries,
    deviceId: DeviceId,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Accounts) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var preSelectedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var currentlyViewedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var preSelectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var currentlyViewedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var transactionRefreshTrigger by remember { mutableStateOf(0) }

    // Use schema-error-aware collection for flows that may fail on old databases
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    MaterialTheme {
        Scaffold(
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
                        onClick = { currentScreen = Screen.Accounts },
                    )
                    NavigationBarItem(
                        icon = { Text("\uD83D\uDCB1") },
                        label = { Text("Currencies") },
                        selected = currentScreen is Screen.Currencies,
                        onClick = { currentScreen = Screen.Currencies },
                    )
                    NavigationBarItem(
                        icon = { Text("\uD83D\uDCC1") },
                        label = { Text("Categories") },
                        selected = currentScreen is Screen.Categories,
                        onClick = { currentScreen = Screen.Categories },
                    )
                    NavigationBarItem(
                        icon = { Text("\uD83D\uDCC4") },
                        label = { Text("CSV") },
                        selected = currentScreen is Screen.CsvImports || currentScreen is Screen.CsvImportDetail,
                        onClick = { currentScreen = Screen.CsvImports },
                    )
                    NavigationBarItem(
                        icon = { Text("\u2699\uFE0F") },
                        label = { Text("Settings") },
                        selected = currentScreen is Screen.Settings,
                        onClick = { currentScreen = Screen.Settings },
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
                            preSelectedCurrencyId = currentlyViewedCurrencyId
                            showTransactionDialog = true
                        },
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineLarge)
                    }
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (val screen = currentScreen) {
                    is Screen.Accounts -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        AccountsScreen(
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            transactionRepository = transactionRepository,
                            onAccountClick = { account ->
                                currentScreen = Screen.AccountTransactions(account.id, account.name)
                            },
                        )
                    }
                    is Screen.Currencies -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        CurrenciesScreen(currencyRepository)
                    }
                    is Screen.Categories -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        CategoriesScreen(
                            categoryRepository = categoryRepository,
                            currencyRepository = currencyRepository,
                        )
                    }
                    is Screen.Settings -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        SettingsScreen(
                            currencyRepository = currencyRepository,
                            categoryRepository = categoryRepository,
                            accountRepository = accountRepository,
                            attributeTypeRepository = attributeTypeRepository,
                            transactionRepository = transactionRepository,
                            maintenanceService = maintenanceService,
                            transferSourceQueries = transferSourceQueries,
                            deviceId = deviceId,
                        )
                    }
                    is Screen.AccountTransactions -> {
                        // Initialize currentlyViewedAccountId when first entering the screen
                        LaunchedEffect(screen.accountId) {
                            currentlyViewedAccountId = screen.accountId
                        }
                        AccountTransactionsScreen(
                            accountId = currentlyViewedAccountId ?: screen.accountId,
                            transactionRepository = transactionRepository,
                            transferSourceRepository = transferSourceRepository,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            currencyRepository = currencyRepository,
                            auditRepository = auditRepository,
                            attributeTypeRepository = attributeTypeRepository,
                            transferAttributeRepository = transferAttributeRepository,
                            maintenanceService = maintenanceService,
                            currentDeviceId = deviceId,
                            onAccountIdChange = { accountId ->
                                currentlyViewedAccountId = accountId
                            },
                            onCurrencyIdChange = { currencyId ->
                                currentlyViewedCurrencyId = currencyId
                            },
                            scrollToTransferId = screen.scrollToTransferId,
                            externalRefreshTrigger = transactionRefreshTrigger,
                        )
                    }
                    is Screen.CsvImports -> {
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        CsvImportsScreen(
                            csvImportRepository = csvImportRepository,
                            onImportClick = { importId ->
                                currentScreen = Screen.CsvImportDetail(importId)
                            },
                            onStrategiesClick = {
                                currentScreen = Screen.CsvStrategies
                            },
                        )
                    }
                    is Screen.CsvImportDetail -> {
                        CsvImportDetailScreen(
                            importId = screen.importId,
                            csvImportRepository = csvImportRepository,
                            csvImportStrategyRepository = csvImportStrategyRepository,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            currencyRepository = currencyRepository,
                            transactionRepository = transactionRepository,
                            attributeTypeRepository = attributeTypeRepository,
                            maintenanceService = maintenanceService,
                            transferSourceQueries = transferSourceQueries,
                            deviceRepository = deviceRepository,
                            onBack = { currentScreen = Screen.CsvImports },
                            onDeleted = { currentScreen = Screen.CsvImports },
                            onTransferClick = { transferId, isPositiveAmount ->
                                scope.launch {
                                    transactionRepository
                                        .getTransactionById(transferId.id)
                                        .collect { transfer ->
                                            transfer?.let {
                                                // Navigate to target account if positive (money coming in),
                                                // source account if negative (money going out)
                                                val accountId =
                                                    if (isPositiveAmount) {
                                                        transfer.targetAccountId
                                                    } else {
                                                        transfer.sourceAccountId
                                                    }
                                                val account = accounts.find { a -> a.id == accountId }
                                                if (account != null) {
                                                    currentScreen =
                                                        Screen.AccountTransactions(
                                                            accountId = account.id,
                                                            accountName = account.name,
                                                            scrollToTransferId = transferId,
                                                        )
                                                }
                                            }
                                        }
                                }
                            },
                        )
                    }
                    is Screen.CsvStrategies -> {
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        CsvStrategiesScreen(
                            csvImportStrategyRepository = csvImportStrategyRepository,
                            onBack = { currentScreen = Screen.CsvImports },
                        )
                    }
                }
            }
        }

        if (showTransactionDialog) {
            TransactionEntryDialog(
                transactionRepository = transactionRepository,
                transferSourceQueries = transferSourceQueries,
                deviceRepository = deviceRepository,
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                currencyRepository = currencyRepository,
                attributeTypeRepository = attributeTypeRepository,
                maintenanceService = maintenanceService,
                preSelectedSourceAccountId = preSelectedAccountId,
                preSelectedCurrencyId = preSelectedCurrencyId,
                onDismiss = {
                    showTransactionDialog = false
                    preSelectedAccountId = null
                    preSelectedCurrencyId = null
                },
                onTransactionCreated = {
                    transactionRefreshTrigger++
                },
            )
        }
    }
}
