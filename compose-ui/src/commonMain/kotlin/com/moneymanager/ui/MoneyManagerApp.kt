package com.moneymanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.debug.DebugLogScreen
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.TransactionsScreen
import com.moneymanager.ui.util.readAppVersion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
    databasePath: String? = null,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Accounts) }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(currentScreen.title)
                            val version = remember { readAppVersion() }
                            Text(
                                text = "v$version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            databasePath?.let { path ->
                                Text(
                                    text = "Database: $path",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Text("💰") },
                        label = { Text("Accounts") },
                        selected = currentScreen is Screen.Accounts,
                        onClick = { currentScreen = Screen.Accounts },
                    )
                    NavigationBarItem(
                        icon = { Text("📁") },
                        label = { Text("Categories") },
                        selected = currentScreen is Screen.Categories,
                        onClick = { currentScreen = Screen.Categories },
                    )
                    NavigationBarItem(
                        icon = { Text("💸") },
                        label = { Text("Transactions") },
                        selected = currentScreen is Screen.Transactions,
                        onClick = { currentScreen = Screen.Transactions },
                    )
                    NavigationBarItem(
                        icon = { Text("🐛") },
                        label = { Text("Debug") },
                        selected = currentScreen is Screen.Debug,
                        onClick = { currentScreen = Screen.Debug },
                    )
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (currentScreen) {
                    is Screen.Accounts -> AccountsScreen(accountRepository)
                    is Screen.Categories -> CategoriesScreen(categoryRepository)
                    is Screen.Transactions -> TransactionsScreen(transactionRepository)
                    is Screen.Debug -> DebugLogScreen()
                }
            }
        }
    }
}
