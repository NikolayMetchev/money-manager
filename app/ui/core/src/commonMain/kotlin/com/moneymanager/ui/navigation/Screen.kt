@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.navigation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

sealed class Screen(val title: String) {
    data object Accounts : Screen("Accounts")

    data object Currencies : Screen("Currencies")

    data object Categories : Screen("Categories")

    data object People : Screen("People")

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

    data class AccountAuditHistory(val accountId: AccountId, val accountName: String) :
        Screen("Account Audit: $accountName")

    data class PersonAuditHistory(val personId: PersonId, val personName: String) :
        Screen("Person Audit: $personName")

    data class CurrencyAuditHistory(val currencyId: CurrencyId, val currencyCode: String) :
        Screen("Currency Audit: $currencyCode")
}
