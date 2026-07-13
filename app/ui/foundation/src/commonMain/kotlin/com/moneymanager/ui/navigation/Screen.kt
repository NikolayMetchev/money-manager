@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.qif.QifImportId
import kotlinx.serialization.Serializable

enum class ImportTab { DIRECTORIES, CSV, QIF, API, MISC, TIMELINE }

/**
 * Navigation destinations. Serializable [NavKey]s so the back stack survives process death via
 * `rememberNavBackStack` (titles are computed properties, not serialized state).
 */
@Serializable
sealed class Screen : NavKey {
    abstract val title: String

    @Serializable
    data class Accounts(
        val scrollToAccountId: AccountId? = null,
    ) : Screen() {
        override val title: String get() = "Accounts"
    }

    @Serializable
    data object Currencies : Screen() {
        override val title: String get() = "Currencies"
    }

    @Serializable
    data object Categories : Screen() {
        override val title: String get() = "Categories"
    }

    @Serializable
    data object People : Screen() {
        override val title: String get() = "People"
    }

    @Serializable
    data class PeopleScroll(
        val personId: PersonId,
    ) : Screen() {
        override val title: String get() = "People"
    }

    @Serializable
    data class Imports(
        val tab: ImportTab = ImportTab.DIRECTORIES,
    ) : Screen() {
        override val title: String get() = "Imports"
    }

    @Serializable
    data object CsvStrategies : Screen() {
        override val title: String get() = "Import Strategies"
    }

    @Serializable
    data class CsvStrategyEditor(
        val csvImportId: CsvImportId,
        val strategyId: CsvImportStrategyId? = null,
    ) : Screen() {
        override val title: String get() = if (strategyId == null) "Create Strategy" else "Edit Strategy"
    }

    @Serializable
    data object ApiStrategies : Screen() {
        override val title: String get() = "API Import Strategies"
    }

    @Serializable
    data class StrategyCatalog(
        val kindFilter: StrategyKind? = null,
    ) : Screen() {
        override val title: String get() = "Strategy Catalog"
    }

    @Serializable
    data class ApiStrategyEditor(
        val strategyId: ApiImportStrategyId? = null,
    ) : Screen() {
        override val title: String get() = if (strategyId == null) "Create API Strategy" else "Edit API Strategy"
    }

    @Serializable
    data object Settings : Screen() {
        override val title: String get() = "Settings"
    }

    @Serializable
    data object DatabaseSizeBreakdown : Screen() {
        override val title: String get() = "Database Size Breakdown"
    }

    @Serializable
    data class AccountTransactions(
        val accountId: AccountId,
        val accountName: String,
        val scrollToTransferId: TransferId? = null,
        val selectedCurrencyId: CurrencyId? = null,
    ) : Screen() {
        override val title: String get() = accountName
    }

    @Serializable
    data class CsvImportDetail(
        val importId: CsvImportId,
        val scrollToRowIndex: Long? = null,
    ) : Screen() {
        override val title: String get() = "CSV Import"
    }

    @Serializable
    data class QifImportDetail(
        val importId: QifImportId,
        val scrollToRecordIndex: Long? = null,
    ) : Screen() {
        override val title: String get() = "QIF Import"
    }

    @Serializable
    data class QifStrategyEditor(
        val qifImportId: QifImportId,
        val strategyId: CsvImportStrategyId? = null,
    ) : Screen() {
        override val title: String get() = if (strategyId == null) "Create Strategy" else "Edit Strategy"
    }

    @Serializable
    data class AuditHistory(
        val transferId: TransferId,
    ) : Screen() {
        override val title: String get() = "Audit History"
    }

    @Serializable
    data class AccountAuditHistory(
        val accountId: AccountId,
        val accountName: String,
    ) : Screen() {
        override val title: String get() = "Account Audit: $accountName"
    }

    @Serializable
    data class PersonAuditHistory(
        val personId: PersonId,
        val personName: String,
    ) : Screen() {
        override val title: String get() = "Person Audit: $personName"
    }

    @Serializable
    data class CurrencyAuditHistory(
        val currencyId: CurrencyId,
        val currencyCode: String,
    ) : Screen() {
        override val title: String get() = "Currency Audit: $currencyCode"
    }

    @Serializable
    data class CategoryAuditHistory(
        val categoryId: Long,
        val categoryName: String,
    ) : Screen() {
        override val title: String get() = "Category Audit: $categoryName"
    }

    @Serializable
    data class ApiStrategyAuditHistory(
        val strategyId: ApiImportStrategyId,
        val strategyName: String,
    ) : Screen() {
        override val title: String get() = "API Strategy Audit: $strategyName"
    }

    @Serializable
    data class CsvStrategyAuditHistory(
        val strategyId: CsvImportStrategyId,
        val strategyName: String,
    ) : Screen() {
        override val title: String get() = "CSV Strategy Audit: $strategyName"
    }

    @Serializable
    data class ImportDirectoryAuditHistory(
        val directoryId: ImportDirectoryId,
        val directoryName: String,
    ) : Screen() {
        override val title: String get() = "Import Directory Audit: $directoryName"
    }

    @Serializable
    data object ConnectApi : Screen() {
        override val title: String get() = "Connect API Account"
    }

    @Serializable
    data class ExchangeOrders(
        val accountId: AccountId,
        val accountName: String,
    ) : Screen() {
        override val title: String get() = "$accountName Orders"
    }

    @Serializable
    data class ExchangeOrderDetail(
        val orderId: ExchangeOrderId,
    ) : Screen() {
        override val title: String get() = "Order"
    }

    @Serializable
    data class ExchangeOrderAuditHistory(
        val orderId: ExchangeOrderId,
    ) : Screen() {
        override val title: String get() = "Order Audit"
    }

    @Serializable
    data class ApiSessionTraffic(
        val sessionId: ApiSessionId,
        /** When non-null the traffic screen should scroll to this request/response pair. */
        val highlightRequestId: ApiRequestId? = null,
        /** When non-null the traffic screen should expand and highlight this JSONPath. */
        val highlightJsonPath: String? = null,
    ) : Screen() {
        override val title: String get() = "API Traffic"
    }
}
