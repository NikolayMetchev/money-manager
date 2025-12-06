package com.moneymanager.ui.navigation

import com.moneymanager.domain.model.AccountId

sealed class Screen(val route: String, val title: String) {
    data object Accounts : Screen("accounts", "Accounts")

    data object Currencies : Screen("currencies", "Currencies")

    data object Categories : Screen("categories", "Categories")

    data object Settings : Screen("settings", "Settings")

    data class AccountTransactions(val accountId: AccountId, val accountName: String) :
        Screen("account-transactions", accountName)
}
