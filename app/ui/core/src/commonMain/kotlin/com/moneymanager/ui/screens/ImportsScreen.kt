package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.ui.navigation.ImportTab

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    importDirectoryRepository: ImportDirectoryReadRepository,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    csvImportRepository: CsvImportReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    csvAccountMappingRepository: CsvAccountMappingReadRepository,
    qifImportRepository: QifImportReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    categoryRepository: CategoryReadRepository,
    settingsRepository: SettingsReadRepository,
    apiSessionRepository: ApiSessionReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    transactionRepository: TransactionReadRepository,
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
    onPassThroughAccountsClick: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Pass-through (conduit) accounts, e.g. Curve, apply across every import type (CSV/QIF/API), so
        // the entry lives at the top level rather than under any single import tab.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPassThroughAccountsClick) { Text("Pass-through Accounts") }
        }
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
                selected = selectedTab == ImportTab.MANUAL,
                onClick = { onTabSelected(ImportTab.MANUAL) },
                text = { Text("Manual Entries") },
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
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onImportClick = onCsvImportClick,
                    onStrategiesClick = onCsvStrategiesClick,
                )
            ImportTab.QIF ->
                QifImportsScreen(
                    qifImportRepository = qifImportRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    settingsRepository = settingsRepository,
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
                    passThroughAccountRepository = passThroughAccountRepository,
                    maintenance = maintenance,
                    deviceId = deviceId,
                    onMonzoConnectClick = onAddCredentialClick,
                    onApiStrategiesClick = onApiStrategiesClick,
                    onSessionClick = onSessionClick,
                    onTransactionsImported = onTransactionsImported,
                )
            ImportTab.MANUAL ->
                ManualEntriesScreen(
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    transactionRepository = transactionRepository,
                    maintenance = maintenance,
                    onTransactionsImported = onTransactionsImported,
                )
        }
    }
}
