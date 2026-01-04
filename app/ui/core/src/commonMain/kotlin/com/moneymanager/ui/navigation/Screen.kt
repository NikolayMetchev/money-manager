@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.navigation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

sealed class Screen(val route: String, val title: String) {
    data object Accounts : Screen("accounts", "Accounts")

    data object Currencies : Screen("currencies", "Currencies")

    data object Categories : Screen("categories", "Categories")

    data object CsvImports : Screen("csv-imports", "CSV Imports")

    data object CsvStrategies : Screen("csv-strategies", "Import Strategies")

    data object Settings : Screen("settings", "Settings")

    data class AccountTransactions(
        val accountId: AccountId,
        val accountName: String,
        val scrollToTransferId: TransferId? = null,
    ) : Screen("account-transactions", accountName)

    data class CsvImportDetail(val importId: CsvImportId) :
        Screen("csv-import-detail", "CSV Import")

    data class AuditHistory(val transferId: TransferId) :
        Screen("audit-history", "Audit History")
}
