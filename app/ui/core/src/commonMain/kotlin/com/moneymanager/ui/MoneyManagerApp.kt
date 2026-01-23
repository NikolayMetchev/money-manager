@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.service.CsvStrategyExportService
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
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.NavigationHistory
import com.moneymanager.ui.navigation.PlatformBackHandler
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.navigation.mouseButtonNavigation
import com.moneymanager.ui.screens.AccountAuditScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CsvImportDetailScreen
import com.moneymanager.ui.screens.CsvImportsScreen
import com.moneymanager.ui.screens.CurrenciesScreen
import com.moneymanager.ui.screens.CurrencyAuditScreen
import com.moneymanager.ui.screens.PeopleScreen
import com.moneymanager.ui.screens.PersonAuditScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.csvstrategy.CsvStrategiesScreen
import com.moneymanager.ui.screens.transactions.AccountTransactionsScreen
import com.moneymanager.ui.screens.transactions.TransactionAuditScreen
import com.moneymanager.ui.screens.transactions.TransactionEditDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    appVersion: AppVersion,
    databaseLocation: DbLocation,
    accountRepository: AccountRepository,
    attributeTypeRepository: AttributeTypeRepository,
    auditRepository: AuditRepository,
    categoryRepository: CategoryRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvStrategyExportService: CsvStrategyExportService,
    currencyRepository: CurrencyRepository,
    deviceRepository: DeviceRepository,
    maintenanceService: DatabaseMaintenanceService,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    transactionRepository: TransactionRepository,
    transferAttributeRepository: TransferAttributeRepository,
    transferSourceRepository: TransferSourceRepository,
    transferSourceQueries: TransferSourceQueries,
    deviceId: DeviceId,
) {
    ProvideSchemaAwareScope {
        val scope = rememberSchemaAwareCoroutineScope()
        val navigationHistory = remember { NavigationHistory(Screen.Accounts) }
        val currentScreen = navigationHistory.currentScreen
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
            // Handle system back button (Android) when there's navigation history
            PlatformBackHandler(enabled = navigationHistory.canGoBack) {
                navigationHistory.navigateBack()
            }

            Scaffold(
                modifier =
                    Modifier
                        .fillMaxSize()
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
                            onClick = { navigationHistory.navigateTo(Screen.Accounts) },
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
                            selected = currentScreen is Screen.People,
                            onClick = { navigationHistory.navigateTo(Screen.People) },
                        )
                        NavigationBarItem(
                            icon = { Text("\uD83D\uDCC4") },
                            label = { Text("CSV") },
                            selected = currentScreen is Screen.CsvImports || currentScreen is Screen.CsvImportDetail,
                            onClick = { navigationHistory.navigateTo(Screen.CsvImports) },
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
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                onAccountClick = { account ->
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
                                currencyRepository = currencyRepository,
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
                                categoryRepository = categoryRepository,
                                currencyRepository = currencyRepository,
                            )
                        }
                        is Screen.People -> {
                            // Reset currentlyViewedAccountId and currentlyViewedCurrencyId when on other screens
                            LaunchedEffect(Unit) {
                                currentlyViewedAccountId = null
                                currentlyViewedCurrencyId = null
                            }
                            PeopleScreen(
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
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
                                currencyRepository = currencyRepository,
                                categoryRepository = categoryRepository,
                                accountRepository = accountRepository,
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
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
                                transferSourceQueries = transferSourceQueries,
                                deviceRepository = deviceRepository,
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                currencyRepository = currencyRepository,
                                attributeTypeRepository = attributeTypeRepository,
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                transferAttributeRepository = transferAttributeRepository,
                                maintenanceService = maintenanceService,
                                onAccountIdChange = { accountId ->
                                    currentlyViewedAccountId = accountId
                                },
                                onCurrencyIdChange = { currencyId ->
                                    currentlyViewedCurrencyId = currencyId
                                },
                                onAccountClick = { accountId, accountName ->
                                    navigationHistory.navigateTo(Screen.AccountTransactions(accountId, accountName))
                                },
                                onAuditClick = { transferId ->
                                    navigationHistory.navigateTo(Screen.AuditHistory(transferId))
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
                                    navigationHistory.navigateTo(Screen.CsvImportDetail(importId))
                                },
                                onStrategiesClick = {
                                    navigationHistory.navigateTo(Screen.CsvStrategies)
                                },
                            )
                        }
                        is Screen.CsvImportDetail -> {
                            CsvImportDetailScreen(
                                importId = screen.importId,
                                csvImportRepository = csvImportRepository,
                                csvImportStrategyRepository = csvImportStrategyRepository,
                                csvAccountMappingRepository = csvAccountMappingRepository,
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                currencyRepository = currencyRepository,
                                transactionRepository = transactionRepository,
                                attributeTypeRepository = attributeTypeRepository,
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                maintenanceService = maintenanceService,
                                transferSourceQueries = transferSourceQueries,
                                deviceRepository = deviceRepository,
                                onBack = { navigationHistory.navigateBack() },
                                onDeleted = { navigationHistory.navigateTo(Screen.CsvImports) },
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
                                                        navigationHistory.navigateTo(
                                                            Screen.AccountTransactions(
                                                                accountId = account.id,
                                                                accountName = account.name,
                                                                scrollToTransferId = transferId,
                                                            ),
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
                                csvImportRepository = csvImportRepository,
                                csvAccountMappingRepository = csvAccountMappingRepository,
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                currencyRepository = currencyRepository,
                                attributeTypeRepository = attributeTypeRepository,
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                csvStrategyExportService = csvStrategyExportService,
                                appVersion = appVersion,
                                onBack = { navigationHistory.navigateBack() },
                            )
                        }
                        is Screen.AuditHistory -> {
                            TransactionAuditScreen(
                                transferId = screen.transferId,
                                auditRepository = auditRepository,
                                accountRepository = accountRepository,
                                transactionRepository = transactionRepository,
                                currentDeviceId = deviceId,
                                onBack = { navigationHistory.navigateBack() },
                            )
                        }
                        is Screen.AccountAuditHistory -> {
                            AccountAuditScreen(
                                accountId = screen.accountId,
                                auditRepository = auditRepository,
                                accountRepository = accountRepository,
                                onBack = { navigationHistory.navigateBack() },
                            )
                        }
                        is Screen.PersonAuditHistory -> {
                            PersonAuditScreen(
                                personId = screen.personId,
                                auditRepository = auditRepository,
                                personRepository = personRepository,
                                onBack = { navigationHistory.navigateBack() },
                            )
                        }
                        is Screen.CurrencyAuditHistory -> {
                            CurrencyAuditScreen(
                                currencyId = screen.currencyId,
                                auditRepository = auditRepository,
                                currencyRepository = currencyRepository,
                                onBack = { navigationHistory.navigateBack() },
                            )
                        }
                    }
                }
            }

            if (showTransactionDialog) {
                TransactionEditDialog(
                    transaction = null,
                    transactionRepository = transactionRepository,
                    transferSourceRepository = transferSourceRepository,
                    transferSourceQueries = transferSourceQueries,
                    deviceRepository = deviceRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    attributeTypeRepository = attributeTypeRepository,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    transferAttributeRepository = transferAttributeRepository,
                    maintenanceService = maintenanceService,
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
