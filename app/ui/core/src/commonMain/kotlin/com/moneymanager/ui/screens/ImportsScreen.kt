package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.QifImportRepository
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.importer.ImportEngine
import com.moneymanager.ui.navigation.ImportTab

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    qifImportRepository: QifImportRepository,
    categoryRepository: CategoryRepository,
    settingsRepository: SettingsRepository,
    apiSessionRepository: ApiSessionRepository,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    entitySource: EntitySource,
    maintenance: Maintenance,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
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
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    attributeTypeRepository = attributeTypeRepository,
                    settingsRepository = settingsRepository,
                    maintenance = maintenance,
                    entitySource = entitySource,
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
                    transactionRepository = transactionRepository,
                    entitySource = entitySource,
                    maintenance = maintenance,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    personAttributeRepository = personAttributeRepository,
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
                    entitySource = entitySource,
                    maintenance = maintenance,
                    onTransactionsImported = onTransactionsImported,
                )
        }
    }
}
