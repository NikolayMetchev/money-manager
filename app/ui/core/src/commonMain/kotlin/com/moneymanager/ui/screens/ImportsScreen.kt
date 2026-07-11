package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.screens.accountmapping.AccountMappingsScreen

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    importDirectoryRepository: ImportDirectoryReadRepository,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    csvImportRepository: CsvImportReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountMappingExportService: AccountMappingExportService,
    appVersion: AppVersion,
    qifImportRepository: QifImportReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    categoryRepository: CategoryReadRepository,
    settingsRepository: SettingsReadRepository,
    apiSessionRepository: ApiSessionReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    personRepository: PersonReadRepository,
    importEngine: ImportEngine,
    deviceId: DeviceId,
    onCsvImportClick: (CsvImportId) -> Unit,
    onCsvStrategiesClick: () -> Unit,
    onQifImportClick: (QifImportId) -> Unit,
    onAddCredentialClick: () -> Unit,
    onApiStrategiesClick: () -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onImportDirectoryAuditClick: (ImportDirectory) -> Unit,
    onTransactionsImported: () -> Unit,
    onBrowsePassThroughCatalog: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == ImportTab.DIRECTORIES,
                onClick = { onTabSelected(ImportTab.DIRECTORIES) },
                text = { Text("Directories") },
            )
            Tab(
                selected = selectedTab == ImportTab.CSV,
                onClick = { onTabSelected(ImportTab.CSV) },
                text = { Text("CSV") },
            )
            Tab(
                selected = selectedTab == ImportTab.QIF,
                onClick = { onTabSelected(ImportTab.QIF) },
                text = { Text("QIF") },
            )
            Tab(
                selected = selectedTab == ImportTab.API,
                onClick = { onTabSelected(ImportTab.API) },
                text = { Text("API") },
            )
            Tab(
                selected = selectedTab == ImportTab.MISC,
                onClick = { onTabSelected(ImportTab.MISC) },
                text = { Text("Misc") },
            )
        }

        when (selectedTab) {
            ImportTab.DIRECTORIES ->
                ImportDirectoriesScreen(
                    importDirectoryRepository = importDirectoryRepository,
                    csvImportRepository = csvImportRepository,
                    qifImportRepository = qifImportRepository,
                    deviceId = deviceId,
                    importFileSourceFactory = importFileSourceFactory,
                    driveFolderBrowser = driveFolderBrowser,
                    onOpenImports = onTabSelected,
                    onOpenAudit = onImportDirectoryAuditClick,
                )
            ImportTab.CSV ->
                CsvImportsScreen(
                    csvImportRepository = csvImportRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    personRepository = personRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    transactionRepository = transactionRepository,
                    transferRelationshipRepository = transferRelationshipRepository,
                    transferSourceRepository = transferSourceRepository,
                    tradeRepository = tradeRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onImportClick = onCsvImportClick,
                    onStrategiesClick = onCsvStrategiesClick,
                )
            ImportTab.QIF ->
                QifImportsScreen(
                    qifImportRepository = qifImportRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    settingsRepository = settingsRepository,
                    transactionRepository = transactionRepository,
                    transferSourceRepository = transferSourceRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onImportClick = onQifImportClick,
                    onStrategiesClick = onCsvStrategiesClick,
                )
            ImportTab.API ->
                ApiSessionsScreen(
                    apiSessionRepository = apiSessionRepository,
                    apiImportStrategyRepository = apiImportStrategyRepository,
                    accountAttributeRepository = accountAttributeRepository,
                    accountRepository = accountRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    maintenance = maintenance,
                    deviceId = deviceId,
                    onMonzoConnectClick = onAddCredentialClick,
                    onApiStrategiesClick = onApiStrategiesClick,
                    onSessionClick = onSessionClick,
                    onTransactionsImported = onTransactionsImported,
                )
            ImportTab.MISC ->
                MiscImportsTab(
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    transactionRepository = transactionRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    accountMappingExportService = accountMappingExportService,
                    appVersion = appVersion,
                    importEngine = importEngine,
                    maintenance = maintenance,
                    onTransactionsImported = onTransactionsImported,
                    onBrowsePassThroughCatalog = onBrowsePassThroughCatalog,
                )
        }
    }
}

/** Sub-tabs under the "Misc" import tab: manual transaction entry, and pass-through (conduit) config. */
@Composable
private fun MiscImportsTab(
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    transactionRepository: TransactionReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    accountMappingExportService: AccountMappingExportService,
    appVersion: AppVersion,
    importEngine: ImportEngine,
    maintenance: Maintenance,
    onTransactionsImported: () -> Unit,
    onBrowsePassThroughCatalog: () -> Unit,
) {
    var subTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Manual Entries") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Pass-through") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Account Mappings") })
        }
        when (subTab) {
            0 ->
                ManualEntriesScreen(
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    transactionRepository = transactionRepository,
                    maintenance = maintenance,
                    onTransactionsImported = onTransactionsImported,
                )
            1 ->
                PassThroughAccountsScreen(
                    passThroughAccountRepository = passThroughAccountRepository,
                    importEngine = importEngine,
                    appVersion = appVersion,
                    onBrowseCatalog = onBrowsePassThroughCatalog,
                )
            else ->
                AccountMappingsScreen(
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    accountMappingExportService = accountMappingExportService,
                    appVersion = appVersion,
                )
        }
    }
}
