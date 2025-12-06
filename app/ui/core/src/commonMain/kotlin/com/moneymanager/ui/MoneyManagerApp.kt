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
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountTransactionsScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.CurrenciesScreen
import com.moneymanager.ui.screens.SettingsScreen
import com.moneymanager.ui.screens.TransactionEntryDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
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

    val accounts by repositorySet.accountRepository.getAllAccounts().collectAsState(initial = emptyList())
    val currencies by repositorySet.currencyRepository.getAllCurrencies().collectAsState(initial = emptyList())

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
                        CategoriesScreen(repositorySet.categoryRepository)
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
                currencyRepository = repositorySet.currencyRepository,
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
