@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.navigation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

sealed class Screen(val title: String) {
    data object Accounts : Screen("Accounts")

    data object Currencies : Screen("Currencies")

    data object Categories : Screen("Categories")

    data object CsvImports : Screen("CSV Imports")

    data object CsvStrategies : Screen("Import Strategies")

    data object Settings : Screen("Settings")

    data class AccountTransactions(
        val accountId: AccountId,
        val accountName: String,
        val scrollToTransferId: TransferId? = null,
    ) : Screen(accountName)

    data class CsvImportDetail(val importId: CsvImportId) :
        Screen("CSV Import")

    data class AuditHistory(val transferId: TransferId) :
        Screen("Audit History")
}
