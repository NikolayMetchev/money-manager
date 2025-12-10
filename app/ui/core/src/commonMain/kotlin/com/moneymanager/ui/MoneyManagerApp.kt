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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.DatabaseState
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountTransactionsScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CurrenciesScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.TransactionEntryDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    onLog: (String, Throwable?) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<DatabaseState>(DatabaseState.NoDatabaseSelected) }
    var schemaErrorInfo by remember { mutableStateOf<Pair<DbLocation, Throwable>?>(null) }

    // Observe global schema error state from Flow collection error handlers
    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()

    // Initialize database on startup
    LaunchedEffect(Unit) {
        val defaultLocation = databaseManager.getDefaultLocation()
        val dbExists = databaseManager.databaseExists(defaultLocation)
        onLog("Default database location: $defaultLocation, exists: $dbExists", null)

        if (dbExists) {
            onLog("Existing database found, opening...", null)
            try {
                val database = databaseManager.openDatabase(defaultLocation)
                val repositories = RepositorySet(database)
                // Schema errors at runtime are now caught globally by the uncaught exception handler
                // which updates GlobalSchemaErrorState - no need for explicit validation queries here
                databaseState = DatabaseState.DatabaseLoaded(defaultLocation, repositories)
                onLog("Database opened successfully", null)
            } catch (e: Exception) {
                onLog("Failed to open database: ${e.message}", e)
                // Store error info to show schema error dialog
                schemaErrorInfo = defaultLocation to e
                databaseState = DatabaseState.Error(e)
            }
        } else {
            onLog("No existing database, creating new one...", null)
            try {
                val database = databaseManager.openDatabase(defaultLocation)
                val repositories = RepositorySet(database)
                databaseState = DatabaseState.DatabaseLoaded(defaultLocation, repositories)
                onLog("New database created successfully", null)
            } catch (e: Exception) {
                onLog("Failed to create database: ${e.message}", e)
                schemaErrorInfo = defaultLocation to e
                databaseState = DatabaseState.Error(e)
            }
        }
    }

    // Determine which error to show - prioritize global errors (runtime) over local (startup)
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError?.let { info ->
            // For global errors, get the current database location from state if available
            val location =
                (databaseState as? DatabaseState.DatabaseLoaded)?.location
                    ?: databaseManager.getDefaultLocation()
            location to info.error
        } ?: schemaErrorInfo

    // Show main app once database is loaded
    when (val state = databaseState) {
        is DatabaseState.DatabaseLoaded -> {
            MoneyManagerAppContent(
                repositorySet = state.repositories,
                appVersion = appVersion,
                databaseLocation = state.location,
            )
        }
        is DatabaseState.NoDatabaseSelected -> {
            // Loading...
        }
        is DatabaseState.Error -> {
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
                        val repositories = RepositorySet(database)
                        databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                        schemaErrorInfo = null
                        GlobalSchemaErrorState.clearError()
                        onLog("New database created successfully", null)
                    } catch (e: Exception) {
                        onLog("Failed to backup and create new database: ${e.message}", e)
                        schemaErrorInfo = location to e
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
                        val repositories = RepositorySet(database)
                        databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                        schemaErrorInfo = null
                        GlobalSchemaErrorState.clearError()
                        onLog("New database created successfully", null)
                    } catch (e: Exception) {
                        onLog("Failed to delete and create new database: ${e.message}", e)
                        schemaErrorInfo = location to e
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoneyManagerAppContent(
    repositorySet: RepositorySet,
    appVersion: AppVersion,
    databaseLocation: DbLocation,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Accounts) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var preSelectedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var currentlyViewedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var preSelectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var currentlyViewedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }

    // Use schema-error-aware collection for flows that may fail on old databases
    val accounts by repositorySet.accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by repositorySet.currencyRepository.getAllCurrencies()
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
                                    text = "v${appVersion.value}",
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
                        icon = { Text("💰") },
                        label = { Text("Accounts") },
                        selected = currentScreen is Screen.Accounts || currentScreen is Screen.AccountTransactions,
                        onClick = { currentScreen = Screen.Accounts },
                    )
                    NavigationBarItem(
                        icon = { Text("💱") },
                        label = { Text("Currencies") },
                        selected = currentScreen is Screen.Currencies,
                        onClick = { currentScreen = Screen.Currencies },
                    )
                    NavigationBarItem(
                        icon = { Text("📁") },
                        label = { Text("Categories") },
                        selected = currentScreen is Screen.Categories,
                        onClick = { currentScreen = Screen.Categories },
                    )
                    NavigationBarItem(
                        icon = { Text("⚙️") },
                        label = { Text("Settings") },
                        selected = currentScreen is Screen.Settings,
                        onClick = { currentScreen = Screen.Settings },
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        preSelectedAccountId = currentlyViewedAccountId
                        preSelectedCurrencyId = currentlyViewedCurrencyId
                        showTransactionDialog = true
                    },
                ) {
                    Text("+", style = MaterialTheme.typography.headlineLarge)
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
                            accountRepository = repositorySet.accountRepository,
                            categoryRepository = repositorySet.categoryRepository,
                            transactionRepository = repositorySet.transactionRepository,
                            currencyRepository = repositorySet.currencyRepository,
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
                        CurrenciesScreen(repositorySet.currencyRepository)
                    }
                    is Screen.Categories -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        CategoriesScreen(
                            categoryRepository = repositorySet.categoryRepository,
                            currencyRepository = repositorySet.currencyRepository,
                        )
                    }
                    is Screen.Settings -> {
                        // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                        LaunchedEffect(Unit) {
                            currentlyViewedAccountId = null
                            currentlyViewedCurrencyId = null
                        }
                        SettingsScreen(repositorySet = repositorySet)
                    }
                    is Screen.AccountTransactions -> {
                        // Initialize currentlyViewedAccountId when first entering the screen
                        LaunchedEffect(screen.accountId) {
                            currentlyViewedAccountId = screen.accountId
                        }
                        AccountTransactionsScreen(
                            accountId = screen.accountId,
                            transactionRepository = repositorySet.transactionRepository,
                            accountRepository = repositorySet.accountRepository,
                            currencyRepository = repositorySet.currencyRepository,
                            onAccountIdChange = { accountId ->
                                currentlyViewedAccountId = accountId
                            },
                            onCurrencyIdChange = { currencyId ->
                                currentlyViewedCurrencyId = currencyId
                            },
                        )
                    }
                }
            }
        }

        if (showTransactionDialog) {
            TransactionEntryDialog(
                transactionRepository = repositorySet.transactionRepository,
                accountRepository = repositorySet.accountRepository,
                categoryRepository = repositorySet.categoryRepository,
                currencyRepository = repositorySet.currencyRepository,
                maintenanceService = repositorySet.maintenanceService,
                accounts = accounts,
                currencies = currencies,
                preSelectedSourceAccountId = preSelectedAccountId,
                preSelectedCurrencyId = preSelectedCurrencyId,
                onDismiss = {
                    showTransactionDialog = false
                    preSelectedAccountId = null
                    preSelectedCurrencyId = null
                },
            )
        }
    }
}
