package com.moneymanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.database.di.AppServices
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.navigation.NavigationHistory
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.screens.apistrategy.ApiImportStrategyAuditScreen
import com.moneymanager.ui.screens.apistrategy.ApiStrategiesScreen
import com.moneymanager.ui.screens.apistrategy.editor.ApiStrategyEditorScreen
import com.moneymanager.ui.screens.csv.CsvImportDetailScreen
import com.moneymanager.ui.screens.csvstrategy.CsvImportStrategyAuditScreen
import com.moneymanager.ui.screens.csvstrategy.CsvStrategiesScreen
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorScreen
import com.moneymanager.ui.screens.settings.StrategyCatalogScreen

@Suppress("LongParameterList")
internal fun EntryProviderScope<NavKey>.importStrategyEntries(
    services: AppServices,
    appVersion: AppVersion,
    strategyCatalogController: StrategyCatalogController?,
    navigationHistory: NavigationHistory,
    offerStrategyReimport: (Set<StrategyKey>) -> Unit,
    reimports: Reimports,
    resetViewedIds: @Composable () -> Unit,
    onCsvSourceClick: (CsvImportId, Long) -> Unit,
    onTransferClick: (TransferId, Boolean) -> Unit,
) {
    entry<Screen.StrategyCatalog> { screen ->
        resetViewedIds()
        if (strategyCatalogController != null) {
            StrategyCatalogScreen(
                controller = strategyCatalogController,
                library = services.imports.strategyLibrary,
                appVersion = appVersion,
                accountRepository = services.accounts.accountRepository,
                categoryRepository = services.accounts.categoryRepository,
                currencyRepository = services.accounts.currencyRepository,
                personRepository = services.people.personRepository,
                initialKindFilter = screen.kindFilter,
                onStrategiesUpdated = offerStrategyReimport,
                onBack = { navigationHistory.navigateBack() },
            )
        } else {
            LaunchedEffect(Unit) { navigationHistory.navigateBack() }
        }
    }
    entry<Screen.ApiStrategies> {
        resetViewedIds()
        ApiStrategiesScreen(
            onStrategyUpdated = { offerStrategyReimport(setOf(StrategyKey(StrategyKind.API, it))) },
            apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
            onBrowseCatalog = {
                navigationHistory.navigateTo(Screen.StrategyCatalog(StrategyKind.API))
            },
            onBack = { navigationHistory.navigateBack() },
            onCreateStrategy = {
                navigationHistory.navigateTo(Screen.ApiStrategyEditor())
            },
            onEditStrategy = { strategyId ->
                navigationHistory.navigateTo(Screen.ApiStrategyEditor(strategyId))
            },
            onAuditHistoryClick = { strategy ->
                navigationHistory.navigateTo(
                    Screen.ApiStrategyAuditHistory(strategy.id, strategy.name),
                )
            },
        )
    }
    entry<Screen.ApiStrategyEditor> { screen ->
        resetViewedIds()
        ApiStrategyEditorScreen(
            onStrategyUpdated = { offerStrategyReimport(setOf(StrategyKey(StrategyKind.API, it))) },
            strategyId = screen.strategyId,
            apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
            apiSessionRepository = services.imports.apiSessionRepository,
            accountRepository = services.accounts.accountRepository,
            categoryRepository = services.accounts.categoryRepository,
            personRepository = services.people.personRepository,
            onBack = { navigationHistory.navigateBack() },
        )
    }
    entry<Screen.CsvImportDetail>(
        clazzContentKey = { "CsvImportDetail:${it.importId}" },
    ) { screen ->
        CsvImportDetailScreen(
            importId = screen.importId,
            scrollToRowIndex = screen.scrollToRowIndex,
            columnFilter = screen.columnFilter,
            csvImportRepository = services.imports.csvImportRepository,
            csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
            accountMappingRepository = services.imports.accountMappingRepository,
            accountRepository = services.accounts.accountRepository,
            accountAttributeRepository = services.accounts.accountAttributeRepository,
            categoryRepository = services.accounts.categoryRepository,
            currencyRepository = services.accounts.currencyRepository,
            personRepository = services.people.personRepository,
            passThroughAccountRepository = services.imports.passThroughAccountRepository,
            cryptoRepository = services.accounts.cryptoRepository,
            importDirectoryRepository = services.imports.importDirectoryRepository,
            maintenance = services.imports.maintenance,
            transferSourceRepository = services.transactions.transferSourceRepository,
            tradeRepository = services.transactions.tradeRepository,
            auditRepository = services.audit.auditRepository,
            importEngine = services.transactions.importEngine,
            reimports = reimports,
            onBack = { navigationHistory.navigateBack() },
            onDeleted = { navigationHistory.navigateTo(Screen.Imports(ImportTab.FILES)) },
            onCreateStrategy = { importId ->
                navigationHistory.navigateTo(Screen.CsvStrategyEditor(importId))
            },
            onCsvSourceClick = onCsvSourceClick,
            onTransferClick = onTransferClick,
        )
    }
    entry<Screen.CsvStrategies> {
        resetViewedIds()
        CsvStrategiesScreen(
            onStrategyUpdated = { offerStrategyReimport(setOf(StrategyKey(StrategyKind.CSV, it))) },
            onBrowseCatalog = {
                navigationHistory.navigateTo(Screen.StrategyCatalog(StrategyKind.CSV))
            },
            csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
            csvImportRepository = services.imports.csvImportRepository,
            accountRepository = services.accounts.accountRepository,
            categoryRepository = services.accounts.categoryRepository,
            currencyRepository = services.accounts.currencyRepository,
            personRepository = services.people.personRepository,
            csvStrategyImportExport = services.imports.csvStrategyImportExport,
            strategyLibrary = services.imports.strategyLibrary,
            appVersion = appVersion,
            onBack = { navigationHistory.navigateBack() },
            onEditStrategy = { strategyId, importId ->
                navigationHistory.navigateTo(Screen.CsvStrategyEditor(importId, strategyId))
            },
            onAuditHistoryClick = { strategy ->
                navigationHistory.navigateTo(
                    Screen.CsvStrategyAuditHistory(strategy.id, strategy.name),
                )
            },
        )
    }
    entry<Screen.CsvStrategyEditor> { screen ->
        CsvStrategyEditorScreen(
            onStrategyUpdated = { offerStrategyReimport(setOf(StrategyKey(StrategyKind.CSV, it))) },
            csvImportId = screen.csvImportId,
            strategyId = screen.strategyId,
            csvImportRepository = services.imports.csvImportRepository,
            csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
            accountMappingRepository = services.imports.accountMappingRepository,
            accountRepository = services.accounts.accountRepository,
            categoryRepository = services.accounts.categoryRepository,
            currencyRepository = services.accounts.currencyRepository,
            attributeTypeRepository = services.transactions.attributeTypeRepository,
            personRepository = services.people.personRepository,
            onBack = { navigationHistory.navigateBack() },
        )
    }
    entry<Screen.ApiStrategyAuditHistory> { screen ->
        ApiImportStrategyAuditScreen(
            strategyId = screen.strategyId,
            auditRepository = services.audit.auditRepository,
            apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
            onBack = { navigationHistory.navigateBack() },
        )
    }
    entry<Screen.CsvStrategyAuditHistory> { screen ->
        CsvImportStrategyAuditScreen(
            strategyId = screen.strategyId,
            auditRepository = services.audit.auditRepository,
            csvImportStrategyRepository = services.imports.csvImportStrategyRepository,
            onBack = { navigationHistory.navigateBack() },
        )
    }
}
