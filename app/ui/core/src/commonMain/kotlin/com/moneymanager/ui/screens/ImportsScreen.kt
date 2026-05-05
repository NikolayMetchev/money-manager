package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.navigation.ImportTab

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    csvImportRepository: CsvImportRepository,
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    entitySourceQueries: EntitySourceQueries,
    maintenanceService: DatabaseMaintenanceService,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    deviceId: DeviceId,
    onCsvImportClick: (CsvImportId) -> Unit,
    onCsvStrategiesClick: () -> Unit,
    onAddCredentialClick: () -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onTransactionsImported: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == ImportTab.CSV,
                onClick = { onTabSelected(ImportTab.CSV) },
                text = { Text("CSV") },
            )
            Tab(
                selected = selectedTab == ImportTab.API,
                onClick = { onTabSelected(ImportTab.API) },
                text = { Text("API") },
            )
        }
        HorizontalDivider()

        when (selectedTab) {
            ImportTab.CSV ->
                CsvImportsScreen(
                    csvImportRepository = csvImportRepository,
                    onImportClick = onCsvImportClick,
                    onStrategiesClick = onCsvStrategiesClick,
                )
            ImportTab.API ->
                ApiSessionsScreen(
                    apiSessionRepository = apiSessionRepository,
                    accountRepository = accountRepository,
                    currencyRepository = currencyRepository,
                    transactionRepository = transactionRepository,
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = entitySourceQueries,
                    maintenanceService = maintenanceService,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    deviceId = deviceId,
                    onMonzoConnectClick = onAddCredentialClick,
                    onSessionClick = onSessionClick,
                    onTransactionsImported = onTransactionsImported,
                )
        }
    }
}
