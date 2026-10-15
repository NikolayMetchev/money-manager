@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.moneymanager.ui.screens.importdirectory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importdirectory.DOWNLOAD_ALL_TASK_KEY
import com.moneymanager.importdirectory.FOLDER_DOWNLOAD_TASK_PREFIX
import com.moneymanager.importdirectory.IMPORT_DIRECTORIES_TASK_PREFIX
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.SUBFOLDER_SEARCH_TASK_PREFIX
import com.moneymanager.importdirectory.directoryDownloadTaskKey
import com.moneymanager.importdirectory.directoryRedownloadTaskKey
import com.moneymanager.importdirectory.folderDownloadTaskKey
import com.moneymanager.importdirectory.subfolderSearchTaskKey
import com.moneymanager.importdirectory.summary
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importengineapi.deleteImportDirectory
import com.moneymanager.importengineapi.updateImportDirectory
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.tasks.TaskScope
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.tasks.childrenOf
import com.moneymanager.tasks.formatElapsedTime
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.tasks.LocalTaskManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Manages import directories (local folders / Google Drive folders) and runs manual downloads. A
 * download stages new/changed files into the CSV/QIF Imports tabs; each row links to those tabs to run
 * "Import All". Downloads run through [importDirectoryService] as tasks; other writes go through the import
 * engine. [driveFolderBrowser] powers the Drive folder picker.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun ImportDirectoriesScreen(
    importDirectoryService: ImportDirectoryService,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    qifImportRepository: QifImportReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    deviceId: DeviceId,
    driveFolderBrowser: DriveFolderBrowser?,
    onOpenImports: (ImportTab) -> Unit = {},
    onOpenAudit: (ImportDirectory) -> Unit = {},
    // Unimports one staged CSV file; offered by re-download for files whose imported rows changed. Null
    // hides that option.
    unimportCsv: (suspend (CsvImportId) -> Unit)? = null,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()

    val directories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        importDirectoryRepository.getAllDirectories()
    }
    // All staged imports, used to tell whether a directory's downloaded files are still un-imported.
    val csvImports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        csvImportRepository.getAllImports()
    }
    val qifImports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        qifImportRepository.getAllImports()
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var redownloadTarget by remember { mutableStateOf<ImportDirectory?>(null) }
    // A message about something this screen did itself (adding a directory); a newer run's outcome replaces it.
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Every download, re-download and "Download all" runs as a task, so it carries on if this screen is left
    // and its outcome and per-file failures (detail and issues) are still there when the screen comes back.
    val directoryRuns = tasks.filter { it.parentId == null && it.key.startsWith(IMPORT_DIRECTORIES_TASK_PREFIX) }
    val downloadAllTask = directoryRuns.lastOrNull { it.key == DOWNLOAD_ALL_TASK_KEY }
    val downloadAllRunning = downloadAllTask?.isRunning == true
    val scanning = directoryRuns.any { it.isRunning }
    val lastRun = directoryRuns.lastOrNull { !it.isRunning }

    // Each folder's own download/search child (whichever run started it) drives its row's progress bar.
    fun rowProgress(directory: ImportDirectory): Pair<Int, Int>? {
        val running = tasks.filter { it.isRunning }
        running.lastOrNull { it.key == folderDownloadTaskKey(directory.id) }?.let { folder ->
            return (folder.progress.processed ?: 0) to (folder.progress.total ?: 0)
        }
        return (0 to 0).takeIf { running.any { it.key == subfolderSearchTaskKey(directory.id) } }
    }

    fun startRun(
        key: String,
        title: String,
        block: suspend TaskScope.() -> String,
    ) {
        statusMessage = null
        taskManager.start(key, title, "Connecting…", block)
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // FlowRow so the buttons wrap below the title on narrow (phone) screens instead of being
        // pushed off the right edge.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Import Directories",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !scanning && directories.any(importDirectoryService::isScannable),
                    onClick = {
                        startRun(DOWNLOAD_ALL_TASK_KEY, "Download all") { importDirectoryService.downloadAll(this).summary() }
                    },
                ) { Text("Download all") }
                Button(onClick = { showAddDialog = true }) { Text("Add directory") }
            }
        }

        Text(
            "\"Download & find subfolders\" on a top-level folder fetches its own files AND discovers importable " +
                "subfolders; download those too, then use each row's import link. Tick \"Exclude\" to skip a folder.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (downloadAllTask != null && downloadAllRunning) {
            DownloadAllProgress(task = downloadAllTask, children = tasks.childrenOf(downloadAllTask.id))
        }

        // The last finished run's outcome, kept by the task manager so it survives leaving this screen.
        val lastOutcome = lastRun?.takeUnless { statusMessage != null }
        (statusMessage ?: lastOutcome?.outcome())?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        lastOutcome?.issues.orEmpty().forEach { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (directories.isEmpty()) {
            Text("No import directories configured yet.", style = MaterialTheme.typography.bodyMedium)
        }

        // Hierarchical view: each top-level folder, then its discovered subfolders indented by depth.
        val ordered =
            buildList {
                directories.filter { it.parentId == null }.forEach { top ->
                    add(top to 0.dp)
                    val rootDepth = (top.displayPath ?: top.folderRef).split(" / ").size
                    directories
                        .filter { it.parentId == top.id }
                        .sortedBy { it.displayPath ?: it.folderRef }
                        .forEach { child ->
                            val depth = ((child.displayPath ?: child.folderRef).split(" / ").size - rootDepth).coerceAtLeast(1)
                            add(child to (depth * 16).dp)
                        }
                }
            }

        ordered.forEach { (directory, indent) ->
            key(directory.id) {
                ImportDirectoryRow(
                    directory = directory,
                    importDirectoryRepository = importDirectoryRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    csvImports = csvImports,
                    qifImports = qifImports,
                    deviceId = deviceId,
                    indent = indent,
                    canDownload = importDirectoryService.isScannable(directory) && !scanning,
                    scanProgress = rowProgress(directory),
                    onDownload = {
                        startRun(directoryDownloadTaskKey(directory.id), "Download ${directory.name}") {
                            importDirectoryService.downloadDirectory(this, directory).summary()
                        }
                    },
                    canRedownload = !scanning && importDirectoryService.redownloadScope(directory, directories).isNotEmpty(),
                    onRedownload = { redownloadTarget = directory },
                    onToggleExclude = {
                        scope.launch { importEngine.updateImportDirectory(directory.copy(excluded = !directory.excluded)) }
                    },
                    onAccountChanged = { accountId ->
                        scope.launch { importEngine.updateImportDirectory(directory.copy(accountId = accountId)) }
                    },
                    onImport = onOpenImports,
                    onAudit = { onOpenAudit(directory) },
                    onDelete = { scope.launch { importEngine.deleteImportDirectory(directory.id) } },
                )
            }
        }
    }

    redownloadTarget?.let { target ->
        RedownloadDirectoryDialog(
            directory = target,
            folders = importDirectoryService.redownloadScope(target, directories),
            canUnimport = unimportCsv != null,
            onDismiss = { redownloadTarget = null },
            onConfirm = { unimportBlocked ->
                redownloadTarget = null
                val folders = importDirectoryService.redownloadScope(target, directories)
                startRun(directoryRedownloadTaskKey(target.id), "Re-download ${target.name}") {
                    importDirectoryService.redownload(this, folders, unimportCsv.takeIf { unimportBlocked }).summary()
                }
            },
        )
    }

    if (showAddDialog) {
        AddImportDirectoryDialog(
            driveFolderBrowser = driveFolderBrowser,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            onDismiss = { showAddDialog = false },
            onCreate = { name, provider, folderRef, displayPath, accountId ->
                showAddDialog = false
                scope.launch {
                    importEngine.createImportDirectory(
                        ImportDirectory(
                            id = ImportDirectoryId(Uuid.random()),
                            name = name,
                            provider = provider,
                            folderRef = folderRef,
                            displayPath = displayPath,
                            deviceId = if (provider == ImportDirectoryProvider.LOCAL) deviceId else null,
                            accountId = accountId,
                            createdAt = Clock.System.now(),
                            updatedAt = Clock.System.now(),
                        ),
                    )
                    statusMessage = "Added $name. Download it to fetch files and discover subfolders."
                }
            },
        )
    }
}

/**
 * The overall "Download all" bar: files done / total summed over every queued folder. The total keeps
 * growing while subfolders are still being found or folders are still listing their files, so the text
 * says when more may come. The elapsed time ticks every second.
 */
@Composable
private fun DownloadAllProgress(
    task: TaskState,
    children: List<TaskState>,
) {
    // A folder that failed before listing its files never counted toward the total, so it drops out.
    val folders = children.filter { it.key.startsWith(FOLDER_DOWNLOAD_TASK_PREFIX) }
    val listed = folders.mapNotNull { folder -> folder.progress.total?.let { (folder.progress.processed ?: 0) to it } }
    val done = listed.sumOf { it.first }
    val total = listed.sumOf { it.second }
    val stillListing = folders.count { it.isRunning && it.progress.total == null }
    val discoveringFolders = children.count { it.isRunning && it.key.startsWith(SUBFOLDER_SEARCH_TASK_PREFIX) }
    val pending =
        buildList {
            if (discoveringFolders > 0) add("still finding subfolders")
            if (stillListing > 0) add("$stillListing folder${if (stillListing == 1) "" else "s"} still listing files")
        }
    var elapsed by remember(task.id) { mutableStateOf(task.elapsed()) }
    LaunchedEffect(task.id) {
        while (true) {
            elapsed = task.elapsed()
            delay(1.seconds)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Downloading all (${formatElapsedTime(elapsed)}): $done / $total files done, ${total - done} left" +
                if (pending.isEmpty()) "" else " (${pending.joinToString()} — total may grow)",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (total > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun ImportDirectoryRow(
    directory: ImportDirectory,
    importDirectoryRepository: ImportDirectoryReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    csvImports: List<CsvImport>,
    qifImports: List<QifImport>,
    deviceId: DeviceId,
    indent: Dp,
    canDownload: Boolean,
    // done / total while this directory is downloading, null when idle.
    scanProgress: Pair<Int, Int>?,
    onDownload: () -> Unit,
    canRedownload: Boolean,
    onRedownload: () -> Unit,
    onToggleExclude: () -> Unit,
    onAccountChanged: (AccountId?) -> Unit,
    onImport: (ImportTab) -> Unit,
    onAudit: () -> Unit,
    onDelete: () -> Unit,
) {
    val trackedFiles by rememberFlowAsStateWithSchemaErrorHandling(directory.id, initial = emptyList()) {
        importDirectoryRepository.getTrackedFiles(directory.id)
    }

    val isDownloading = scanProgress != null
    val csvCount = trackedFiles.count { it.csvImportId != null }
    val qifCount = trackedFiles.count { it.qifImportId != null }
    // lastAppliedAt != null means the staged file has been imported (a strategy was applied).
    val csvImported = remember(csvImports) { csvImports.associate { it.id to (it.lastAppliedAt != null) } }
    val qifImported = remember(qifImports) { qifImports.associate { it.id to (it.lastAppliedAt != null) } }
    // "Outstanding" = a downloaded file whose staging row still exists and hasn't been imported yet.
    val outstandingCsv = trackedFiles.any { it.csvImportId != null && csvImported[it.csvImportId] == false }
    val outstandingQif = trackedFiles.any { it.qifImportId != null && qifImported[it.qifImportId] == false }

    val downloadedSummary =
        listOfNotNull(
            csvCount.takeIf { it > 0 }?.let { "$it csv" },
            qifCount.takeIf { it > 0 }?.let { "$it qif" },
        ).joinToString(" and ").ifEmpty { "No" }.let { "$it files downloaded" }

    val onWrongDevice = !directory.isOn(deviceId)
    // Top-level folders download their own files AND discover/create child directories for subfolders;
    // discovered subfolders just download their files.
    val actionLabel = if (directory.topLevel) "Download & find subfolders" else "Download"

    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }
    val accountName = directory.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.name }
    var showAccountDialog by remember { mutableStateOf(false) }
    if (showAccountDialog) {
        var pendingAccountId by remember { mutableStateOf(directory.accountId) }
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("Account for files in this folder") },
            text = {
                AccountPicker(
                    selectedAccountId = pendingAccountId,
                    onAccountSelected = { pendingAccountId = it },
                    label = "Account (used for strategies with no source-account mapping)",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAccountChanged(pendingAccountId)
                    showAccountDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (pendingAccountId != null) {
                        TextButton(onClick = {
                            onAccountChanged(null)
                            showAccountDialog = false
                        }) { Text("Clear") }
                    }
                    TextButton(onClick = { showAccountDialog = false }) { Text("Cancel") }
                }
            },
        )
    }

    Card(modifier = Modifier.fillMaxWidth().padding(start = indent)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    directory.displayPath ?: directory.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = directory.excluded, onCheckedChange = { onToggleExclude() })
                    Text("Exclude", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(providerLabel(directory.provider), style = MaterialTheme.typography.bodySmall)
            Text(
                accountName?.let { "Account: $it" } ?: "Account: (inherited / picked at import time)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(downloadedSummary, style = MaterialTheme.typography.bodySmall)
            if (onWrongDevice) {
                Text("Configured on another device — download from that device.", style = MaterialTheme.typography.bodySmall)
            }
            if (isDownloading) {
                val (done, total) = scanProgress
                Text(
                    if (total > 0) "Downloading… $done / $total files" else "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            // FlowRow: the download button plus four links don't fit one line on phones.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canDownload && !onWrongDevice && !directory.excluded,
                    onClick = onDownload,
                ) { Text(if (isDownloading) "Working…" else actionLabel) }
                TextButton(enabled = canRedownload && !onWrongDevice, onClick = onRedownload) { Text("Re-download") }
                // One link per type; enabled only while that type has downloaded files still to import.
                TextButton(
                    enabled = outstandingCsv,
                    onClick = { onImport(ImportTab.CSV) },
                ) { Text("CSV imports") }
                TextButton(
                    enabled = outstandingQif,
                    onClick = { onImport(ImportTab.QIF) },
                ) { Text("QIF imports") }
                TextButton(onClick = onAudit) { Text("History") }
                TextButton(onClick = { showAccountDialog = true }) { Text("Set account") }
                TextButton(enabled = !isDownloading, onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun providerLabel(provider: ImportDirectoryProvider): String =
    when (provider) {
        ImportDirectoryProvider.LOCAL -> "Local folder"
        ImportDirectoryProvider.GDRIVE -> "Google Drive"
    }

/**
 * Confirms re-downloading every staged CSV in [folders] ([directory] and its subfolders). [canUnimport]
 * offers to unimport the files whose imported rows the new copy no longer has, instead of skipping them.
 */
@Composable
private fun RedownloadDirectoryDialog(
    directory: ImportDirectory,
    folders: List<ImportDirectory>,
    canUnimport: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (unimportBlocked: Boolean) -> Unit,
) {
    var unimportBlocked by remember { mutableStateOf(false) }
    val subfolders = folders.size - 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-download ${directory.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append("Every CSV file already downloaded from this folder")
                        if (subfolders > 0) append(" and its $subfolders subfolder(s)")
                        append(
                            " is fetched again and re-read with the current parser, replacing its staged copy. " +
                                "Imported rows keep their transactions; files whose rows changed shape can then be " +
                                "re-imported.",
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (canUnimport) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = unimportBlocked, onCheckedChange = { unimportBlocked = it })
                        Text(
                            "Unimport files whose imported rows are no longer in the folder's copy, then re-download them " +
                                "(otherwise they are skipped)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(unimportBlocked) }) { Text("Re-download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun TaskState.outcome(): String =
    when (status) {
        TaskStatus.CANCELLED -> "$title cancelled after ${formatElapsedTime(elapsed())}."
        else -> progress.detail
    }
