package com.moneymanager.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Accounts : Screen("accounts", "Accounts")

    data object Assets : Screen("assets", "Assets")

    data object Categories : Screen("categories", "Categories")

    data object Transactions : Screen("transactions", "Transactions")
}
