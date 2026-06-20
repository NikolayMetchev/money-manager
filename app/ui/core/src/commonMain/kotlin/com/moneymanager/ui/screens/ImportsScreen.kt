package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.ApiSessionWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.navigation.ImportTab

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    csvImportRepository: CsvImportWriteRepository,
    csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    csvAccountMappingRepository: CsvAccountMappingWriteRepository,
    qifImportRepository: QifImportWriteRepository,
    categoryRepository: CategoryReadRepository,
    settingsRepository: SettingsWriteRepository,
    apiSessionRepository: ApiSessionWriteRepository,
    apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    attributeTypeRepository: AttributeTypeWriteRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountWriteRepository,
    currencyRepository: CurrencyWriteRepository,
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
    onTransactionsImported: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
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
            ImportTab.CSV ->
                CsvImportsScreen(
                    csvImportRepository = csvImportRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    attributeTypeRepository = attributeTypeRepository,
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
                    attributeTypeRepository = attributeTypeRepository,
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
                    attributeTypeRepository = attributeTypeRepository,
                    accountAttributeRepository = accountAttributeRepository,
                    accountRepository = accountRepository,
                    currencyRepository = currencyRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
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
                    attributeTypeRepository = attributeTypeRepository,
                    maintenance = maintenance,
                    onTransactionsImported = onTransactionsImported,
                )
        }
    }
}
