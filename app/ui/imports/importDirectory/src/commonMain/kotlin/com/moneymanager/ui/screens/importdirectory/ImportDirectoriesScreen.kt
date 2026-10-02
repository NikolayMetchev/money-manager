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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.csvimporter.ScanResult
import com.moneymanager.csvimporter.discoverImportableFolders
import com.moneymanager.csvimporter.redownloadImportDirectory
import com.moneymanager.csvimporter.scanImportDirectory
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
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importengineapi.deleteImportDirectory
import com.moneymanager.importengineapi.updateImportDirectory
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.ui.background.formatElapsedTime
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.navigation.ImportTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Manages import directories (local folders / Google Drive folders) and runs manual downloads. A
 * download stages new/changed files into the CSV/QIF Imports tabs; each row links to those tabs to run
 * "Import All". Writes go through the import engine; the file source for a download is resolved by
 * [importFileSourceFactory] (null disables it). [driveFolderBrowser] powers the Drive folder picker.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun ImportDirectoriesScreen(
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    qifImportRepository: QifImportReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    deviceId: DeviceId,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    onOpenImports: (ImportTab) -> Unit = {},
    onOpenAudit: (ImportDirectory) -> Unit = {},
    // Unimports one staged CSV file; offered by re-download for files whose imported rows changed. Null
    // hides that option.
    unimportCsv: (suspend (CsvImportId) -> Unit)? = null,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()

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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Per-file scan failures ("file name: reason") from the last download, shown under the status line.
    var scanFailures by remember { mutableStateOf<List<String>>(emptyList()) }
    // done / total of each in-progress download, keyed by directory; drives the per-row progress bars.
    // All of this state is written from IO-dispatcher coroutines, which snapshot state allows.
    val scanProgress = remember { mutableStateMapOf<ImportDirectoryId, Pair<Int, Int>>() }
    // Top-level folders whose subfolder tree is still being walked (the value is unused).
    val discovering = remember { mutableStateMapOf<ImportDirectoryId, Unit>() }
    // Every folder "Download all" has queued, kept after it finishes so the overall bar adds up the whole
    // run: null until the folder's file list comes back, then its done / total.
    val downloadAllProgress = remember { mutableStateMapOf<ImportDirectoryId, Pair<Int, Int>?>() }
    var downloadAllRunning by remember { mutableStateOf(false) }
    // Set for a whole re-download run: its per-folder progress entries come and go between folders, and
    // nothing else may start in those gaps.
    var redownloadRunning by remember { mutableStateOf(false) }
    var downloadAllStartedAt by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    val scanning = downloadAllRunning || redownloadRunning || scanProgress.isNotEmpty() || discovering.isNotEmpty()
    // Directories scan concurrently but the engine doesn't serialize writes, so all of them share it.
    val dbLock = remember { Mutex() }

    fun scannable(directory: ImportDirectory): Boolean =
        importFileSourceFactory != null &&
            importFileSourceFactory.supportsProvider(directory.provider) &&
            (directory.provider != ImportDirectoryProvider.LOCAL || directory.deviceId == deviceId)

    // Walks [root]'s folder tree all the way down and creates a (non-top-level) import directory for
    // every NEW subfolder that contains importable files, calling [onCreated] as each one is created so
    // the caller can download it while the walk goes on. Does NOT download files itself. Returns the
    // count created. [dirsByRef] is updated with the new directories (keyed by folder ref).
    suspend fun createSubdirectories(
        root: ImportDirectory,
        dirsByRef: MutableMap<String, ImportDirectory>,
        onCreated: (ImportDirectory) -> Unit = {},
    ): Int {
        val factory = importFileSourceFactory ?: return 0
        discovering[root.id] = Unit
        try {
            var created = 0
            discoverImportableFolders(
                rootFolderRef = root.folderRef,
                rootDisplayPath = root.displayPath ?: root.folderRef,
                openFolder = { ref -> factory.create(probeDirectory(root, ref)) },
                onFound = { folder ->
                    dbLock.withLock {
                        if (folder.folderRef !in dirsByRef) {
                            val leaf =
                                ImportDirectory(
                                    id = ImportDirectoryId(Uuid.random()),
                                    name = folder.displayPath,
                                    provider = root.provider,
                                    folderRef = folder.folderRef,
                                    displayPath = folder.displayPath,
                                    providerConfig = root.providerConfig,
                                    deviceId = root.deviceId,
                                    topLevel = false,
                                    parentId = root.id,
                                    createdAt = Clock.System.now(),
                                    updatedAt = Clock.System.now(),
                                )
                            importEngine.createImportDirectory(leaf)
                            dirsByRef[folder.folderRef] = leaf
                            created++
                            onCreated(leaf)
                        }
                    }
                },
            )
            return created
        } finally {
            discovering.remove(root.id)
        }
    }

    // Downloads [directory]'s own importable files, showing its row as busy ("Connecting…" until the
    // file count is known) while it runs. [onProgress] additionally sees every done / total update.
    suspend fun downloadFolder(
        directory: ImportDirectory,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult =
        try {
            scanProgress[directory.id] = 0 to 0
            scanImportDirectory(
                directory = directory,
                fileSource = importFileSourceFactory!!.create(directory),
                importDirectoryRepository = importDirectoryRepository,
                csvImportRepository = csvImportRepository,
                qifImportRepository = qifImportRepository,
                importEngine = importEngine,
                dbLock = dbLock,
                onProgress = { done, total ->
                    scanProgress[directory.id] = done to total
                    onProgress(done, total)
                },
            )
        } finally {
            scanProgress.remove(directory.id)
        }

    // Per-row action: a top-level folder both downloads its OWN importable files and discovers +
    // creates child directories for any subfolders; a discovered subfolder just downloads its files.
    fun downloadDirectory(directory: ImportDirectory) {
        statusMessage = null
        scanFailures = emptyList()
        scope.launch {
            try {
                coroutineScope {
                    val created =
                        async(Dispatchers.IO) {
                            if (directory.topLevel) {
                                createSubdirectories(directory, directories.associateByTo(mutableMapOf()) { it.folderRef })
                            } else {
                                null
                            }
                        }
                    val result = withContext(Dispatchers.IO) { downloadFolder(directory) }
                    scanFailures = result.failures
                    val failedSuffix = if (result.filesFailed > 0) ", ${result.filesFailed} failed" else ""
                    val createdCount = created.await()
                    statusMessage =
                        if (createdCount != null) {
                            "${directory.name}: downloaded ${result.filesDownloaded} file(s)$failedSuffix; " +
                                "created $createdCount subfolder director${if (createdCount == 1) "y" else "ies"}."
                        } else {
                            "${directory.name}: downloaded ${result.filesDownloaded} file(s)$failedSuffix."
                        }
                }
            } catch (expected: Exception) {
                statusMessage = "${directory.name}: failed — ${expected.message}"
            }
        }
    }

    // The folders a re-download of [directory] covers: itself, plus its discovered subfolders when it is a
    // top-level folder. Excluded folders and ones configured on another device are skipped, as for download.
    fun redownloadScope(directory: ImportDirectory): List<ImportDirectory> =
        (listOf(directory) + directories.filter { it.parentId == directory.id })
            .filter { scannable(it) && !it.excluded }

    // Re-downloads every staged CSV in [folders] and re-stages each in place with the current parser.
    fun redownload(
        folders: List<ImportDirectory>,
        unimportBlocked: Boolean,
    ) {
        statusMessage = null
        scanFailures = emptyList()
        scope.launch {
            redownloadRunning = true
            try {
                var result = DirectoryRedownloadResult()
                for (folder in folders) {
                    try {
                        scanProgress[folder.id] = 0 to 0
                        result +=
                            withContext(Dispatchers.IO) {
                                dbLock.withLock {
                                    redownloadImportDirectory(
                                        directory = folder,
                                        fileSource = importFileSourceFactory!!.create(folder),
                                        importDirectoryRepository = importDirectoryRepository,
                                        csvImportRepository = csvImportRepository,
                                        importEngine = importEngine,
                                        unimport = unimportCsv.takeIf { unimportBlocked },
                                        onProgress = { done, total -> scanProgress[folder.id] = done to total },
                                    )
                                }
                            }
                    } catch (expected: CancellationException) {
                        throw expected
                    } catch (expected: Exception) {
                        result += DirectoryRedownloadResult(failures = listOf("${folder.name}: ${expected.message}"))
                    } finally {
                        scanProgress.remove(folder.id)
                    }
                }
                statusMessage = result.summary()
                scanFailures =
                    result.failures +
                    result.blocked.map { "$it: imported rows are not in the new copy; re-download with \"unimport\" ticked" } +
                    result.reimportSuggested.map { "$it: columns changed; Re-import it to apply them to its transactions" }
            } finally {
                redownloadRunning = false
            }
        }
    }

    // Downloads every included folder at once. Known folders start straight away; subfolders discovered
    // under the top-level folders start as soon as they are found, so the overall total grows over the run.
    fun downloadAll() {
        statusMessage = null
        scanFailures = emptyList()
        downloadAllProgress.clear()
        val startedAt = TimeSource.Monotonic.markNow()
        downloadAllStartedAt = startedAt
        downloadAllRunning = true
        scope.launch {
            try {
                val dirsByRef = directories.associateByTo(mutableMapOf()) { it.folderRef }
                val results = mutableListOf<Pair<ImportDirectory, ScanResult>>()
                // Folder-level errors (a listing or discovery that threw), kept apart from per-file failures.
                val folderFailures = mutableListOf<String>()
                val resultsLock = Mutex()

                // One folder failing must not cancel the rest of the run, so each one's error is recorded
                // against it instead of propagating to the shared scope.
                suspend fun <T> containingFailure(
                    dir: ImportDirectory,
                    what: String,
                    fallback: T,
                    block: suspend () -> T,
                ): T =
                    try {
                        block()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (expected: Exception) {
                        resultsLock.withLock { folderFailures += "${dir.name} — couldn't $what: ${expected.message}" }
                        fallback
                    }
                val created =
                    coroutineScope {
                        val runScope = this

                        fun startDownload(dir: ImportDirectory) {
                            downloadAllProgress[dir.id] = null
                            runScope.launch(Dispatchers.IO) {
                                val result =
                                    containingFailure(dir, "download this folder", null) {
                                        downloadFolder(dir) { done, total -> downloadAllProgress[dir.id] = done to total }
                                    }
                                if (result == null) {
                                    // Its files never got counted, so drop it rather than leave it "still listing".
                                    downloadAllProgress.remove(dir.id)
                                } else {
                                    resultsLock.withLock { results += dir to result }
                                }
                            }
                        }

                        dirsByRef.values.filter { scannable(it) && !it.excluded }.forEach(::startDownload)
                        directories
                            .filter { it.topLevel && scannable(it) && !it.excluded }
                            .map { root ->
                                async(Dispatchers.IO) {
                                    containingFailure(root, "search its subfolders", 0) {
                                        createSubdirectories(root, dirsByRef, ::startDownload)
                                    }
                                }
                            }.awaitAll()
                            .sum()
                    }
                val downloaded = results.sumOf { (_, result) -> result.filesDownloaded }
                val failed = results.sumOf { (_, result) -> result.filesFailed }
                // Prefix with the directory so the same filename in two folders stays tellable apart.
                scanFailures = folderFailures + results.flatMap { (dir, result) -> result.failures.map { "${dir.name} — $it" } }
                val failedSuffix =
                    listOfNotNull(
                        "$failed file(s) failed".takeIf { failed > 0 },
                        "${folderFailures.size} folder(s) failed".takeIf { folderFailures.isNotEmpty() },
                    ).joinToString(separator = "") { "; $it" }
                statusMessage =
                    "Created $created new director${if (created == 1) "y" else "ies"}; downloaded $downloaded file(s)$failedSuffix " +
                    "in ${formatElapsedTime(startedAt.elapsedNow())}."
            } catch (expected: Exception) {
                statusMessage = "Download all failed after ${formatElapsedTime(startedAt.elapsedNow())} — ${expected.message}"
            } finally {
                downloadAllRunning = false
            }
        }
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
                    enabled = !scanning && directories.any(::scannable),
                    onClick = { downloadAll() },
                ) { Text("Download all") }
                Button(onClick = { showAddDialog = true }) { Text("Add directory") }
            }
        }

        Text(
            "\"Download & find subfolders\" on a top-level folder fetches its own files AND discovers importable " +
                "subfolders; download those too, then use each row's import link. Tick \"Exclude\" to skip a folder.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val startedAt = downloadAllStartedAt
        if (downloadAllRunning && startedAt != null) {
            DownloadAllProgress(
                startedAt = startedAt,
                folderProgress = downloadAllProgress.values.toList(),
                discoveringFolders = discovering.size,
            )
        }

        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        scanFailures.forEach { failure ->
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
                    canDownload = importFileSourceFactory != null && !scanning,
                    scanProgress = scanProgress[directory.id] ?: (0 to 0).takeIf { directory.id in discovering },
                    onDownload = { downloadDirectory(directory) },
                    canRedownload = importFileSourceFactory != null && !scanning && redownloadScope(directory).isNotEmpty(),
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
            folders = redownloadScope(target),
            canUnimport = unimportCsv != null,
            onDismiss = { redownloadTarget = null },
            onConfirm = { unimportBlocked ->
                redownloadTarget = null
                redownload(redownloadScope(target), unimportBlocked)
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
    startedAt: TimeSource.Monotonic.ValueTimeMark,
    folderProgress: List<Pair<Int, Int>?>,
    discoveringFolders: Int,
) {
    val listed = folderProgress.filterNotNull()
    val done = listed.sumOf { it.first }
    val total = listed.sumOf { it.second }
    val stillListing = folderProgress.size - listed.size
    val pending =
        buildList {
            if (discoveringFolders > 0) add("still finding subfolders")
            if (stillListing > 0) add("$stillListing folder${if (stillListing == 1) "" else "s"} still listing files")
        }
    var elapsed by remember(startedAt) { mutableStateOf(startedAt.elapsedNow()) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = startedAt.elapsedNow()
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

    val onWrongDevice = directory.provider == ImportDirectoryProvider.LOCAL && directory.deviceId != deviceId
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

// A throwaway directory used only to open an arbitrary folder [folderRef] via the file-source factory
// (which reads provider/folderRef/providerConfig); other fields are inherited from [root].
private fun probeDirectory(
    root: ImportDirectory,
    folderRef: String,
): ImportDirectory = root.copy(id = ImportDirectoryId(Uuid.random()), folderRef = folderRef)

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

private fun DirectoryRedownloadResult.summary(): String =
    buildString {
        append("Re-downloaded ${replaced.size + unimported.size} file(s)")
        if (unchanged.isNotEmpty()) append(", ${unchanged.size} unchanged")
        if (unimported.isNotEmpty()) append(", ${unimported.size} unimported first")
        if (blocked.isNotEmpty()) append(", ${blocked.size} skipped")
        if (failures.isNotEmpty()) append(", ${failures.size} failed")
        append(".")
    }
