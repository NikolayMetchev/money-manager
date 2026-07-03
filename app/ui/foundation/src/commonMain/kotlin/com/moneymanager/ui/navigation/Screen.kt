@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.navigation

import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.qif.QifImportId

enum class ImportTab { DIRECTORIES, CSV, QIF, API, MISC }

sealed class Screen(
    val title: String,
) {
    data class Accounts(
        val scrollToAccountId: AccountId? = null,
    ) : Screen("Accounts")

    data object Currencies : Screen("Currencies")

    data object Categories : Screen("Categories")

    data object People : Screen("People")

    data class PeopleScroll(
        val personId: PersonId,
    ) : Screen("People")

    data class Imports(
        val tab: ImportTab = ImportTab.DIRECTORIES,
    ) : Screen("Imports")

    data object CsvStrategies : Screen("Import Strategies")

    data class CsvStrategyEditor(
        val csvImportId: CsvImportId,
        val strategyId: CsvImportStrategyId? = null,
    ) : Screen(if (strategyId == null) "Create Strategy" else "Edit Strategy")

    data object ApiStrategies : Screen("API Import Strategies")

    data class StrategyCatalog(
        val kindFilter: StrategyKind? = null,
    ) : Screen("Strategy Catalog")

    data class ApiStrategyEditor(
        val strategyId: ApiImportStrategyId? = null,
    ) : Screen(if (strategyId == null) "Create API Strategy" else "Edit API Strategy")

    data object Settings : Screen("Settings")

    data object DatabaseSizeBreakdown : Screen("Database Size Breakdown")

    data class AccountTransactions(
        val accountId: AccountId,
        val accountName: String,
        val scrollToTransferId: TransferId? = null,
        val selectedCurrencyId: CurrencyId? = null,
    ) : Screen(accountName)

    data class CsvImportDetail(
        val importId: CsvImportId,
        val scrollToRowIndex: Long? = null,
    ) : Screen("CSV Import")

    data class QifImportDetail(
        val importId: QifImportId,
        val scrollToRecordIndex: Long? = null,
    ) : Screen("QIF Import")

    data class QifStrategyEditor(
        val qifImportId: QifImportId,
        val strategyId: CsvImportStrategyId? = null,
    ) : Screen(if (strategyId == null) "Create Strategy" else "Edit Strategy")

    data class AuditHistory(
        val transferId: TransferId,
    ) : Screen("Audit History")

    data class AccountAuditHistory(
        val accountId: AccountId,
        val accountName: String,
    ) : Screen("Account Audit: $accountName")

    data class PersonAuditHistory(
        val personId: PersonId,
        val personName: String,
    ) : Screen("Person Audit: $personName")

    data class CurrencyAuditHistory(
        val currencyId: CurrencyId,
        val currencyCode: String,
    ) : Screen("Currency Audit: $currencyCode")

    data class CategoryAuditHistory(
        val categoryId: Long,
        val categoryName: String,
    ) : Screen("Category Audit: $categoryName")

    data class ApiStrategyAuditHistory(
        val strategyId: ApiImportStrategyId,
        val strategyName: String,
    ) : Screen("API Strategy Audit: $strategyName")

    data class CsvStrategyAuditHistory(
        val strategyId: CsvImportStrategyId,
        val strategyName: String,
    ) : Screen("CSV Strategy Audit: $strategyName")

    data class ImportDirectoryAuditHistory(
        val directoryId: ImportDirectoryId,
        val directoryName: String,
    ) : Screen("Import Directory Audit: $directoryName")

    data object ConnectApi : Screen("Connect API Account")

    data class ApiSessionTraffic(
        val sessionId: ApiSessionId,
        /** When non-null the traffic screen should scroll to this request/response pair. */
        val highlightRequestId: ApiRequestId? = null,
        /** When non-null the traffic screen should expand and highlight this JSONPath. */
        val highlightJsonPath: String? = null,
    ) : Screen("API Traffic")
}
