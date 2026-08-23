package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberBinaryFilePicker
import com.moneymanager.compose.filepicker.rememberMultipleFilePicker
import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParser
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createXlsxImport
import com.moneymanager.importengineapi.setCsvImportIgnored
import com.moneymanager.ui.components.imports.ImportCardDetailText
import com.moneymanager.ui.components.imports.ImportFileCard
import com.moneymanager.ui.components.imports.ImportStatusMessage
import com.moneymanager.ui.components.imports.ImportTab
import com.moneymanager.ui.components.imports.ImportTabsRow
import com.moneymanager.ui.components.imports.ImportsEmptyMessage
import com.moneymanager.ui.components.imports.ImportsScreenHeader
import com.moneymanager.ui.components.imports.emptyImportTabMessage
import com.moneymanager.ui.components.imports.importPickedFiles
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.sha256Hex
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Suppress("LongParameterList", "LongMethod")
@Composable
fun CsvImportsScreen(
    csvImportRepository: CsvImportReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onImportClick: (CsvImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        csvImportRepository.getAllImports()
    }
    val dateRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importTimelineRepository.getCsvImportDateRanges().map { ranges -> ranges.associateBy { it.fileId } }
    }
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())
    // The account files scanned from each import directory belong to (see ImportDirectory.accountId),
    // used below for files whose applied/matched strategy has no hard-coded SOURCE_ACCOUNT of its own.
    var directoryAccounts by remember { mutableStateOf<Map<CsvImportId, AccountId>>(emptyMap()) }
    LaunchedEffect(imports) {
        directoryAccounts = importDirectoryRepository.csvImportSourceAccounts()
    }

    // Unimported files carry no stored strategy, so match one per file the same way "Import all" does
    // (content/filename-aware, needs each file's columns + sampled rows — not in getAllImports()), to
    // group the Unimported tab by strategy and flag files with no match. Keyed by import id (not
    // position) since `imports` can change under this effect between runs.
    //
    // `strategies` starts as emptyList() before its flow's first emission (the collectAsState initial
    // value), which is indistinguishable from a genuinely empty strategy list — so `strategies.isEmpty()`
    // alone can't tell "still loading" from "user has zero strategies configured". A dedicated
    // one-shot subscription tracks the real load instead: once it flips, an empty `strategies` correctly
    // routes every unimported file to "No strategy" rather than getting stuck in "Matching strategies…".
    var strategiesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        csvImportStrategyRepository.getAllStrategies().first()
        strategiesLoaded = true
    }
    val unimportedForMatching = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
    var matchedStrategies by remember { mutableStateOf<Map<CsvImportId, CsvImportStrategy?>?>(null) }
    LaunchedEffect(unimportedForMatching, strategies, strategiesLoaded) {
        if (!strategiesLoaded) {
            matchedStrategies = null
            return@LaunchedEffect
        }
        matchedStrategies =
            unimportedForMatching.associate { listedImport ->
                val fullImport = csvImportRepository.getImport(listedImport.id).first()
                val strategy =
                    fullImport?.let {
                        val sampleRows =
                            csvImportRepository.getImportRows(listedImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
                        strategies.selectForCsv(it.originalFileName, it.columns, sampleRows)
                    }
                listedImport.id to strategy
            }
    }

    var isImporting by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importMessageIsError by remember { mutableStateOf(false) }

    val filePicker =
        rememberMultipleFilePicker(
            mimeTypes = listOf("text/csv", "text/plain", "text/comma-separated-values"),
        ) { results ->
            if (results.isNotEmpty()) {
                isImporting = true
                importMessage = null
                scope.launch {
                    val outcome =
                        importPickedFiles(results, fileName = { it.fileName }) { result ->
                            val checksum = sha256Hex(result.content)
                            if (csvImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                return@importPickedFiles false
                            }
                            val parser = CsvParser()
                            val delimiter = parser.detectDelimiter(result.content)
                            val parseResult =
                                parser.parse(
                                    result.content,
                                    CsvParseOptions(delimiter = delimiter),
                                )
                            importEngine.createCsvImport(
                                fileName = result.fileName,
                                headers = parseResult.headers,
                                rows = parseResult.rows,
                                fileChecksum = checksum,
                                fileLastModified = result.lastModified ?: Clock.System.now(),
                            )
                            true
                        }
                    isImporting = false
                    importMessageIsError = outcome.isError
                    importMessage = outcome.message
                }
            }
        }

    val xlsxFilePicker =
        rememberBinaryFilePicker(
            mimeTypes = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ) { result ->
            if (result != null) {
                isImporting = true
                importMessage = null
                scope.launch {
                    importMessageIsError = false
                    importMessage =
                        try {
                            val checksum = sha256Hex(result.bytes)
                            if (csvImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                "Skipped ${result.fileName}: already imported"
                            } else {
                                val parser = createXlsxParser()
                                val sheetName = parser.sheetNames(result.bytes).firstOrNull() ?: ""
                                val parsed = parser.parse(result.bytes, sheetName)
                                importEngine.createXlsxImport(
                                    fileName = result.fileName,
                                    headers = parsed.headers,
                                    rows = parsed.rows,
                                    fileChecksum = checksum,
                                    fileLastModified = result.lastModified ?: Clock.System.now(),
                                    xlsxBytes = result.bytes,
                                    xlsxWorksheetName = sheetName,
                                )
                                "Imported ${result.fileName}"
                            }
                        } catch (expected: Exception) {
                            importMessageIsError = true
                            "${result.fileName}: ${expected.message}"
                        }
                    isImporting = false
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        ImportsScreenHeader(
            title = "CSV Imports",
            importButtonLabel = "+ Import CSV",
            isImporting = isImporting,
            onImportClick = { filePicker.launch() },
            onStrategiesClick = onStrategiesClick,
        ) {
            TextButton(
                onClick = { xlsxFilePicker.launch() },
                enabled = !isImporting,
            ) {
                Text("+ Import Excel")
            }
        }

        ImportStatusMessage(message = importMessage, isError = importMessageIsError)

        Spacer(modifier = Modifier.height(16.dp))

        if (imports.isEmpty() && !isImporting) {
            ImportsEmptyMessage("No CSV files imported yet. Click '+ Import CSV' to add one or more.")
        } else {
            // Split files into those still needing a strategy applied vs. already imported, so a large
            // set of files is easy to work through. The Unimported tab is the default/actionable one.
            // Ignored files are dismissed by the user and kept out of both actionable lists.
            val unimported = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
            val importedList = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt != null } }
            val ignoredList = remember(imports) { imports.filter { it.ignored } }
            var selectedTab by remember { mutableStateOf(ImportTab.UNIMPORTED) }

            ImportTabsRow(
                selectedTab = selectedTab,
                unimportedCount = unimported.size,
                importedCount = importedList.size,
                ignoredCount = ignoredList.size,
                onTabSelected = { selectedTab = it },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The list to run "Import all" against (a strategy group's files, or every unimported file
            // when launched from the top-level button); null hides the dialog.
            var importAllScope by remember { mutableStateOf<List<CsvImport>?>(null) }
            // Same idea for "Re-import all".
            var reimportAllScope by remember { mutableStateOf<List<CsvImport>?>(null) }

            if (selectedTab == ImportTab.UNIMPORTED && unimported.isNotEmpty()) {
                Button(
                    onClick = { importAllScope = unimported },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import all (${unimported.size})")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (selectedTab == ImportTab.IMPORTED && importedList.isNotEmpty()) {
                Button(
                    onClick = { reimportAllScope = importedList },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-import all (${importedList.size})")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            importAllScope?.let { scopedUnimported ->
                CsvImportAllDialog(
                    unimported = scopedUnimported,
                    importDirectoryRepository = importDirectoryRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    accountAttributeRepository = accountAttributeRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    personRepository = personRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    csvImportRepository = csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { importAllScope = null },
                    onComplete = { importAllScope = null },
                )
            }

            reimportAllScope?.let { scopedImported ->
                CsvReimportAllDialog(
                    imported = scopedImported,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    accountAttributeRepository = accountAttributeRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    passThroughAccountRepository = passThroughAccountRepository,
                    csvImportRepository = csvImportRepository,
                    transactionRepository = transactionRepository,
                    transferRelationshipRepository = transferRelationshipRepository,
                    transferSourceRepository = transferSourceRepository,
                    tradeRepository = tradeRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { reimportAllScope = null },
                    onComplete = { reimportAllScope = null },
                )
            }

            val shown =
                when (selectedTab) {
                    ImportTab.UNIMPORTED -> unimported
                    ImportTab.IMPORTED -> importedList
                    else -> ignoredList
                }
            if (shown.isEmpty()) {
                ImportsEmptyMessage(emptyImportTabMessage(selectedTab))
            } else if (selectedTab == ImportTab.IGNORED) {
                // Ignored files span every strategy and need no scoped bulk action, so this tab stays flat.
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id.toString() }) { import ->
                        CsvImportCard(
                            import = import,
                            dateRange = dateRanges[import.id.id.toString()],
                            sourceAccountName = resolveSourceAccountName(import, strategies, directoryAccounts, accounts),
                            onClick = { onImportClick(import.id) },
                            ignored = true,
                            onSetIgnored = { ignore ->
                                scope.launch { importEngine.setCsvImportIgnored(import.id, ignore) }
                            },
                        )
                    }
                }
            } else {
                val groups =
                    remember(unimported, importedList, matchedStrategies, selectedTab) {
                        if (selectedTab == ImportTab.UNIMPORTED) {
                            buildUnimportedStrategyGroups(unimported, matchedStrategies)
                        } else {
                            buildImportedStrategyGroups(importedList)
                        }
                    }
                val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEach { group ->
                        val sectionKey = "$selectedTab:${group.key?.toString() ?: "none"}"
                        val expanded = expandedSections[sectionKey] ?: true
                        item(key = "header-$sectionKey") {
                            val isNoStrategyGroup =
                                selectedTab == ImportTab.UNIMPORTED && group.key == null && group.actionable
                            StrategySectionHeader(
                                title = group.label,
                                count = group.imports.size,
                                expanded = expanded,
                                onToggleExpanded = { expandedSections[sectionKey] = !expanded },
                                actionLabel =
                                    when {
                                        !group.actionable -> null
                                        isNoStrategyGroup -> "Ignore all"
                                        selectedTab == ImportTab.UNIMPORTED -> "Import all"
                                        else -> "Re-import all"
                                    },
                                onAction = {
                                    when {
                                        isNoStrategyGroup ->
                                            scope.launch {
                                                group.imports.forEach { importEngine.setCsvImportIgnored(it.id, true) }
                                            }
                                        selectedTab == ImportTab.UNIMPORTED -> importAllScope = group.imports
                                        else -> reimportAllScope = group.imports
                                    }
                                },
                                isWarning = group.isWarning,
                            )
                        }
                        if (expanded) {
                            items(group.imports, key = { it.id.toString() }) { import ->
                                CsvImportCard(
                                    import = import,
                                    dateRange = dateRanges[import.id.id.toString()],
                                    sourceAccountName = resolveSourceAccountName(import, strategies, directoryAccounts, accounts),
                                    matchedStrategyName =
                                        if (selectedTab == ImportTab.UNIMPORTED && group.actionable && !group.isWarning) {
                                            group.label
                                        } else {
                                            null
                                        },
                                    noMatchingStrategy = selectedTab == ImportTab.UNIMPORTED && group.isWarning,
                                    onClick = { onImportClick(import.id) },
                                    ignored = false,
                                    onSetIgnored = { ignore ->
                                        scope.launch { importEngine.setCsvImportIgnored(import.id, ignore) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One strategy's files within a tab; [key] is null for the "no strategy"/"unknown strategy" bucket.
 * [actionable] is false only for the transient "still matching" bucket, which has no scoped action yet.
 */
private data class CsvStrategyGroup(
    val key: CsvImportStrategyId?,
    val label: String,
    val imports: List<CsvImport>,
    val isWarning: Boolean = false,
    val actionable: Boolean = true,
)

/**
 * Groups already-imported files by the strategy they were last applied with — a field every
 * [CsvImport] already carries, so no extra queries are needed. "No strategy" first, this stays sorted
 * by label with a fallback "Unknown strategy" bucket kept last (below) for imports whose applied
 * strategy has since been deleted or otherwise lost its name.
 */
private fun buildImportedStrategyGroups(importedList: List<CsvImport>): List<CsvStrategyGroup> =
    importedList
        .groupBy { it.lastAppliedStrategyId }
        .map { (id, files) ->
            val label = files.firstOrNull { !it.lastAppliedStrategyName.isNullOrBlank() }?.lastAppliedStrategyName
            CsvStrategyGroup(key = id, label = label ?: "Unknown strategy", imports = files)
        }.sortedBy { it.label.lowercase() }

/**
 * Groups unimported files by their auto-matched strategy ([matches], built by content/filename-aware
 * [selectForCsv] since these files have no stored strategy yet). Files with no match ([matches] value
 * null) form a "No strategy" group surfaced first with warning styling, so they're never lost among
 * matched files. While [matches] hasn't finished resolving, every file is shown under one "Matching
 * strategies…" bucket with no scoped action, rather than leaving the tab blank.
 */
private fun buildUnimportedStrategyGroups(
    unimported: List<CsvImport>,
    matches: Map<CsvImportId, CsvImportStrategy?>?,
): List<CsvStrategyGroup> {
    if (matches == null) {
        return listOf(
            CsvStrategyGroup(key = null, label = "Matching strategies…", imports = unimported, actionable = false),
        )
    }
    val byStrategyId = unimported.groupBy { matches[it.id]?.id }
    val noStrategy = byStrategyId[null].orEmpty()
    val strategyById = matches.values.filterNotNull().associateBy { it.id }
    val withStrategy =
        byStrategyId.entries
            .filter { it.key != null }
            .map { (id, files) -> CsvStrategyGroup(key = id, label = strategyById[id]?.name ?: "Unknown strategy", imports = files) }
            .sortedBy { it.label.lowercase() }
    return buildList {
        if (noStrategy.isNotEmpty()) {
            add(CsvStrategyGroup(key = null, label = "No strategy", imports = noStrategy, isWarning = true))
        }
        addAll(withStrategy)
    }
}

@Composable
private fun StrategySectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actionLabel: String?,
    onAction: () -> Unit,
    isWarning: Boolean,
) {
    val titleColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = titleColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isWarning) "⚠ $title ($count)" else "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The account [import]'s transfers were (or will be) created against, for display in the imports list.
 * Prefers the strategy's own hard-coded SOURCE_ACCOUNT (e.g. crypto.com's fixed conduit account) — the
 * true answer whenever one exists — over the file's import-directory account (see
 * `ImportDirectory.accountId`), which only matters for strategies with no fixed source (e.g. Monzo,
 * where the same export format serves any account). Null when neither resolves anything yet (the file
 * has no applied/matched strategy and its directory has none set either).
 */
private fun resolveSourceAccountName(
    import: CsvImport,
    strategies: List<CsvImportStrategy>,
    directoryAccounts: Map<CsvImportId, AccountId>,
    accounts: List<Account>,
): String? {
    val appliedStrategy = import.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
    val hardCodedAccountId = (appliedStrategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT) as? HardCodedAccountMapping)?.accountId
    val accountId = hardCodedAccountId ?: directoryAccounts[import.id]
    return accountId?.let { id -> accounts.firstOrNull { it.id == id }?.name }
}

@Suppress("LongParameterList")
@Composable
private fun CsvImportCard(
    import: CsvImport,
    dateRange: ImportFileDateRange?,
    sourceAccountName: String?,
    onClick: () -> Unit,
    ignored: Boolean,
    onSetIgnored: (Boolean) -> Unit,
    // Auto-matched strategy name for an unimported file (null elsewhere, and null when unmatched — see
    // [noMatchingStrategy]). Already-imported cards show their strategy via `lastAppliedStrategyName` below.
    matchedStrategyName: String? = null,
    noMatchingStrategy: Boolean = false,
) {
    val isImported = import.lastAppliedAt != null
    ImportFileCard(
        fileName = import.originalFileName,
        metadataText = "${import.rowCount} rows, ${import.columnCount} columns",
        addedAt = import.importTimestamp,
        errorCount = import.errorCount,
        lastAppliedAt = import.lastAppliedAt,
        applicationCount = import.applicationCount,
        lastAppliedStrategyName = import.lastAppliedStrategyName,
        dateRange = dateRange,
        ignored = ignored,
        onClick = onClick,
        onSetIgnored = onSetIgnored,
        details = { metadataColor ->
            ImportCardDetailText(
                text = "Source account: ${sourceAccountName ?: "Not set — choose at import"}",
                color = metadataColor,
            )
            if (matchedStrategyName != null) {
                ImportCardDetailText(text = "Strategy: $matchedStrategyName", color = metadataColor)
            } else if (noMatchingStrategy) {
                ImportCardDetailText(text = "⚠ No matching strategy", color = MaterialTheme.colorScheme.error)
            }
        },
        footer = { metadataColor ->
            if (isImported && import.lastAppliedStrategyName.isNullOrBlank()) {
                ImportCardDetailText(text = "Strategy information unavailable", color = metadataColor, spacing = 2.dp)
            }
            if (isImported && import.applicationCount > 1) {
                ImportCardDetailText(text = "Applied ${import.applicationCount} times", color = metadataColor, spacing = 2.dp)
            }
        },
    )
}
