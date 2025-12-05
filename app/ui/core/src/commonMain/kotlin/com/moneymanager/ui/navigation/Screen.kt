package com.moneymanager.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Accounts : Screen("accounts", "Accounts")

    data object Currencies : Screen("currencies", "Currencies")

    data object Categories : Screen("categories", "Categories")

    data class AccountTransactions(val accountId: Long, val accountName: String) :
        Screen("account-transactions", accountName)
}
