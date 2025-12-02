package com.moneymanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountTransactionsScreen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.AssetsScreen
import com.moneymanager.ui.screens.CategoriesScreen
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
    var preSelectedAccountId by remember { mutableStateOf<Long?>(null) }

    val accounts by repositorySet.accountRepository.getAllAccounts().collectAsState(initial = emptyList())
    val assets by repositorySet.assetRepository.getAllAssets().collectAsState(initial = emptyList())

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
                        icon = { Text("💎") },
                        label = { Text("Assets") },
                        selected = currentScreen is Screen.Assets,
                        onClick = { currentScreen = Screen.Assets },
                    )
                    NavigationBarItem(
                        icon = { Text("📁") },
                        label = { Text("Categories") },
                        selected = currentScreen is Screen.Categories,
                        onClick = { currentScreen = Screen.Categories },
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        preSelectedAccountId = (currentScreen as? Screen.AccountTransactions)?.accountId
                        showTransactionDialog = true
                    },
                ) {
                    Text("+", style = MaterialTheme.typography.headlineLarge)
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (val screen = currentScreen) {
                    is Screen.Accounts ->
                        AccountsScreen(
                            accountRepository = repositorySet.accountRepository,
                            transactionRepository = repositorySet.transactionRepository,
                            assetRepository = repositorySet.assetRepository,
                            onAccountClick = { account ->
                                currentScreen = Screen.AccountTransactions(account.id, account.name)
                            },
                        )
                    is Screen.Assets -> AssetsScreen(repositorySet.assetRepository)
                    is Screen.Categories -> CategoriesScreen(repositorySet.categoryRepository)
                    is Screen.AccountTransactions ->
                        AccountTransactionsScreen(
                            accountId = screen.accountId,
                            transactionRepository = repositorySet.transactionRepository,
                            accountRepository = repositorySet.accountRepository,
                            assetRepository = repositorySet.assetRepository,
                        )
                }
            }
        }

        if (showTransactionDialog) {
            TransactionEntryDialog(
                transactionRepository = repositorySet.transactionRepository,
                accountRepository = repositorySet.accountRepository,
                assetRepository = repositorySet.assetRepository,
                accounts = accounts,
                assets = assets,
                preSelectedSourceAccountId = preSelectedAccountId,
                onDismiss = {
                    showTransactionDialog = false
                    preSelectedAccountId = null
                },
            )
        }
    }
}
