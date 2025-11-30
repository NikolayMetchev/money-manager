package com.moneymanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.AccountsScreen
import com.moneymanager.ui.screens.CategoriesScreen
import com.moneymanager.ui.screens.TransactionsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyManagerApp(
    repositorySet: RepositorySet,
    appVersion: AppVersion,
    databaseLocation: DbLocation,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Accounts) }

    MaterialTheme {
        Scaffold(
            topBar = {
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
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (currentScreen) {
                    is Screen.Accounts -> AccountsScreen(repositorySet.accountRepository)
                    is Screen.Categories -> CategoriesScreen(repositorySet.categoryRepository)
                    is Screen.Transactions -> TransactionsScreen(repositorySet.transactionRepository)
                }
            }
        }
    }
}
