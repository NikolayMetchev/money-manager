package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.executeCsvUnimport
import com.moneymanager.csvimporter.planCsvUnimport
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.reconciliation.ReconciliationFocus
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.timeline.ImportFileDateRange
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
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.setCsvImportIgnored
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.ui.components.imports.resolveImportDirectories
import com.moneymanager.ui.components.imports.stagedImportPath
import com.moneymanager.ui.foundation.ReconciliationFocusState
import com.moneymanager.ui.foundation.rememberPersistedState
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.navigation.ReconciliationSubTab
import com.moneymanager.ui.screens.accountmapping.AccountMappingsScreen
import com.moneymanager.ui.screens.apistrategy.ApiSessionsScreen
import com.moneymanager.ui.screens.csv.ImportFilesScreen
import com.moneymanager.ui.screens.importdirectory.ImportDirectoriesSection
import com.moneymanager.ui.screens.reconciliation.CardLast4Screen
import com.moneymanager.ui.screens.reconciliation.MatchPickerState
import com.moneymanager.ui.screens.reconciliation.ReconciliationCache
import com.moneymanager.ui.screens.reconciliation.ReconciliationScreen
import com.moneymanager.ui.screens.timeline.ImportTimelineScreen
import com.moneymanager.ui.screens.transactions.ManualEntriesScreen
import com.moneymanager.ui.screens.transactions.ManualEntrySuggestionCache
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.serializer

@Composable
fun ImportsScreen(
    selectedTab: ImportTab,
    onTabSelected: (ImportTab) -> Unit,
    reconciliationSubTab: ReconciliationSubTab,
    onReconciliationSubTabSelected: (ReconciliationSubTab) -> Unit,
    onOpenReconciliation: (ReconciliationFocus) -> Unit,
    importDirectoryRepository: ImportDirectoryReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    reconciliationRepository: ReconciliationReadRepository,
    importDirectoryService: ImportDirectoryService,
    apiImportService: ApiImportService,
    driveFolderBrowser: DriveFolderBrowser?,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountMappingExportService: AccountMappingExportService,
    appVersion: AppVersion,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    categoryRepository: CategoryReadRepository,
    apiSessionRepository: ApiSessionReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: RelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    personRepository: PersonReadRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    importEngine: ImportEngine,
    deviceId: DeviceId,
    onCsvImportClick: (CsvImportId) -> Unit,
    // Opens a file with its table filtered (column name to the values kept).
    onCsvImportRowsClick: (CsvImportId, Map<String, Set<String>>) -> Unit = { id, _ -> onCsvImportClick(id) },
    onTimelineFileClick: (ImportFileDateRange) -> Unit,
    onCsvStrategiesClick: () -> Unit,
    onAddCredentialClick: () -> Unit,
    onApiStrategiesClick: () -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onImportDirectoryAuditClick: (ImportDirectory) -> Unit,
    onTransactionsImported: () -> Unit,
    onBrowsePassThroughCatalog: () -> Unit = {},
    onReconciliationLegClick: (ReconciliationLeg) -> Unit = {},
    onReconciliationSourceClick: (ReconciliationLeg) -> Unit = {},
    matchPickerState: MatchPickerState = remember { MatchPickerState() },
    reconciliationCache: ReconciliationCache = remember { ReconciliationCache() },
    manualEntrySuggestionCache: ManualEntrySuggestionCache = remember { ManualEntrySuggestionCache() },
    reconciliationFocus: ReconciliationFocusState,
    showImportDirectories: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            ImportTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        when (selectedTab) {
            ImportTab.FILES -> {
                ImportFilesScreen(
                    importDirectoryService = importDirectoryService,
                    csvImportRepository = csvImportRepository,
                    transferSourceRepository = transferSourceRepository,
                    importTimelineRepository = importTimelineRepository,
                    importDirectoryRepository = importDirectoryRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    transactionRepository = transactionRepository,
                    relationshipRepository = relationshipRepository,
                    tradeRepository = tradeRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onImportClick = onCsvImportClick,
                    onStrategiesClick = onCsvStrategiesClick,
                    directories =
                        if (showImportDirectories) {
                            { onShowFiles, onRunStarted ->
                                ImportDirectoriesSection(
                                    importDirectoryService = importDirectoryService,
                                    importDirectoryRepository = importDirectoryRepository,
                                    csvImportRepository = csvImportRepository,
                                    accountRepository = accountRepository,
                                    categoryRepository = categoryRepository,
                                    personRepository = personRepository,
                                    deviceId = deviceId,
                                    driveFolderBrowser = driveFolderBrowser,
                                    onShowFiles = onShowFiles,
                                    onOpenAudit = onImportDirectoryAuditClick,
                                    unimportCsv = { id ->
                                        executeCsvUnimport(
                                            plan = planCsvUnimport(id, csvImportRepository, transferSourceRepository),
                                            accountRepository = accountRepository,
                                            transactionRepository = transactionRepository,
                                            relationshipRepository = relationshipRepository,
                                            tradeRepository = tradeRepository,
                                            maintenance = maintenance,
                                            importEngine = importEngine,
                                        )
                                        // Unimport parks the file on the Ignored tab; re-downloading it is meant to
                                        // import it again.
                                        importEngine.setCsvImportIgnored(id, ignored = false)
                                    },
                                    onRunStarted = onRunStarted,
                                    // The Files tab offers "Download all" in the panel's header.
                                    showDownloadAll = false,
                                )
                            }
                        } else {
                            null
                        },
                )
            }

            ImportTab.API -> {
                ApiSessionsScreen(
                    apiSessionRepository = apiSessionRepository,
                    importTimelineRepository = importTimelineRepository,
                    apiImportStrategyRepository = apiImportStrategyRepository,
                    accountAttributeRepository = accountAttributeRepository,
                    accountRepository = accountRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    maintenance = maintenance,
                    deviceId = deviceId,
                    apiImportService = apiImportService,
                    reimports = importDirectoryService.reimports,
                    onAddCredentialClick = onAddCredentialClick,
                    onApiStrategiesClick = onApiStrategiesClick,
                    onSessionClick = onSessionClick,
                    onTransactionsImported = onTransactionsImported,
                )
            }

            ImportTab.MISC -> {
                MiscImportsTab(
                    passThroughAccountRepository = passThroughAccountRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    accountMappingExportService = accountMappingExportService,
                    appVersion = appVersion,
                    importEngine = importEngine,
                    onBrowsePassThroughCatalog = onBrowsePassThroughCatalog,
                )
            }

            ImportTab.TIMELINE -> {
                ImportTimelineScreen(
                    importTimelineRepository = importTimelineRepository,
                    accountRepository = accountRepository,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    onOpenFile = onTimelineFileClick,
                )
            }

            ImportTab.RECONCILIATION -> {
                ReconciliationImportsTab(
                    subTab = reconciliationSubTab,
                    onSubTabSelected = onReconciliationSubTabSelected,
                    reconciliation = {
                        ReconciliationScreen(
                            reconciliationRepository = reconciliationRepository,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            transactionRepository = transactionRepository,
                            onOpenLeg = onReconciliationLegClick,
                            onOpenSource = onReconciliationSourceClick,
                            pickerState = matchPickerState,
                            cache = reconciliationCache,
                            focus = reconciliationFocus,
                        )
                    },
                    manualEntries = {
                        ManualEntriesScreen(
                            csvImportStrategyRepository = csvImportStrategyRepository,
                            transactionRepository = transactionRepository,
                            tradeRepository = tradeRepository,
                            currencyRepository = currencyRepository,
                            reconciliationRepository = reconciliationRepository,
                            accountRepository = accountRepository,
                            suggestionCache = manualEntrySuggestionCache,
                            onOpenSource = onReconciliationSourceClick,
                            onOpenReconciliation = onOpenReconciliation,
                            maintenance = maintenance,
                            onTransactionsImported = onTransactionsImported,
                        )
                    },
                    cards = {
                        CardLast4Screen(
                            csvImportRepository = csvImportRepository,
                            importDirectoryRepository = importDirectoryRepository,
                            onOpenFile = onCsvImportRowsClick,
                            csvImportStrategyRepository = csvImportStrategyRepository,
                            accountAttributeRepository = accountAttributeRepository,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            prepareReimport = { ids, onProgress ->
                                val reimports = importDirectoryService.reimports
                                val files =
                                    ids.mapNotNull { id -> csvImportRepository.getImport(id).first() }
                                val directories =
                                    resolveImportDirectories(
                                        importDirectoryRepository.csvImportDirectories().first(),
                                        importDirectoryRepository.getAllDirectories().first(),
                                    )
                                val reimport = reimports.prepareAll(files, onProgress) { stagedImportPath(it, directories) }
                                val run: suspend () -> BulkReimportResult = {
                                    reimport().also { if (it.transfersCreated > 0) onTransactionsImported() }
                                }
                                run
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun ReconciliationSubTab.label(): String =
    when (this) {
        ReconciliationSubTab.EXTERNAL -> "External Account Reconciliation"
        ReconciliationSubTab.MANUAL -> "Manual Entries"
        ReconciliationSubTab.CARDS -> "Card Last-4"
    }

/**
 * Sub-tabs under the "Reconciliation" import tab — everything an import leaves for a person to resolve:
 * comparing against an external source's shadow accounts, companion transactions to enter by hand, and
 * card references no account owns yet.
 */
@Composable
private fun ReconciliationImportsTab(
    subTab: ReconciliationSubTab,
    onSubTabSelected: (ReconciliationSubTab) -> Unit,
    reconciliation: @Composable () -> Unit,
    manualEntries: @Composable () -> Unit,
    cards: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = subTab.ordinal) {
            ReconciliationSubTab.entries.forEach { entry ->
                Tab(selected = subTab == entry, onClick = { onSubTabSelected(entry) }, text = { Text(entry.label()) })
            }
        }
        when (subTab) {
            ReconciliationSubTab.EXTERNAL -> reconciliation()
            ReconciliationSubTab.MANUAL -> manualEntries()
            ReconciliationSubTab.CARDS -> cards()
        }
    }
}

/** Sub-tabs under the "Misc" import tab: pass-through (conduit) config and account mappings. */
@Composable
private fun MiscImportsTab(
    passThroughAccountRepository: PassThroughAccountReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    accountMappingExportService: AccountMappingExportService,
    appVersion: AppVersion,
    importEngine: ImportEngine,
    onBrowsePassThroughCatalog: () -> Unit,
) {
    var subTab by rememberPersistedState("imports.miscSubTab", Int.serializer()) { 0 }
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Pass-through") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Account Mappings") })
        }
        when (subTab) {
            0 -> {
                PassThroughAccountsScreen(
                    passThroughAccountRepository = passThroughAccountRepository,
                    importEngine = importEngine,
                    appVersion = appVersion,
                    onBrowseCatalog = onBrowsePassThroughCatalog,
                )
            }

            else -> {
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
}

private val ImportTab.label: String
    get() =
        when (this) {
            ImportTab.FILES -> "Files"
            ImportTab.API -> "API"
            ImportTab.MISC -> "Misc"
            ImportTab.TIMELINE -> "Timeline"
            ImportTab.RECONCILIATION -> "Reconciliation"
        }
