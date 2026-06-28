@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.moneymanager.csvimporter.discoverImportableFolders
import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importengineapi.deleteImportDirectory
import com.moneymanager.importengineapi.updateImportDirectory
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.ImportTab
import kotlinx.coroutines.launch
import kotlin.time.Clock
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
    deviceId: DeviceId,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    onOpenImports: (ImportTab) -> Unit = {},
    onOpenAudit: (ImportDirectory) -> Unit = {},
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()

    val directories by importDirectoryRepository
        .getAllDirectories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    // All staged imports, used to tell whether a directory's downloaded files are still un-imported.
    val csvImports by csvImportRepository.getAllImports().collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val qifImports by qifImportRepository.getAllImports().collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var scanningId by remember { mutableStateOf<ImportDirectoryId?>(null) }
    // done / total of the in-progress download, for the per-directory progress bar.
    var scanProgress by remember { mutableStateOf(0 to 0) }

    fun scannable(directory: ImportDirectory): Boolean =
        importFileSourceFactory != null &&
            importFileSourceFactory.supportsProvider(directory.provider) &&
            (directory.provider != ImportDirectoryProvider.LOCAL || directory.deviceId == deviceId)

    // Walks [root]'s folder tree all the way down and creates a (non-top-level) import directory for
    // every NEW subfolder that contains importable files. Does NOT download files. Returns the count
    // created. [dirsByRef] is updated with the new directories (keyed by folder ref).
    suspend fun createSubdirectories(
        root: ImportDirectory,
        dirsByRef: MutableMap<String, ImportDirectory>,
    ): Int {
        val factory = importFileSourceFactory ?: return 0
        val discovered =
            discoverImportableFolders(
                rootFolderRef = root.folderRef,
                rootDisplayPath = root.displayPath ?: root.folderRef,
                openFolder = { ref -> factory.create(probeDirectory(root, ref)) },
            )
        var created = 0
        for (folder in discovered) {
            if (folder.folderRef in dirsByRef) continue
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
        }
        return created
    }

    suspend fun downloadFolder(directory: ImportDirectory) =
        scanImportDirectory(
            directory = directory,
            fileSource = importFileSourceFactory!!.create(directory),
            importDirectoryRepository = importDirectoryRepository,
            csvImportRepository = csvImportRepository,
            qifImportRepository = qifImportRepository,
            importEngine = importEngine,
            onProgress = { done, total -> scanProgress = done to total },
        )

    // Per-row action: a top-level folder only discovers + creates child directories; a discovered
    // subfolder downloads its own files.
    fun downloadDirectory(directory: ImportDirectory) {
        scanningId = directory.id
        scanProgress = 0 to 0
        statusMessage = null
        scope.launch {
            try {
                if (directory.topLevel) {
                    val created = createSubdirectories(directory, directories.associateByTo(mutableMapOf()) { it.folderRef })
                    statusMessage = "${directory.name}: created $created subfolder director${if (created == 1) "y" else "ies"}."
                } else {
                    statusMessage = "${directory.name}: downloaded ${downloadFolder(directory).filesDownloaded} file(s)."
                }
            } catch (expected: Exception) {
                statusMessage = "${directory.name}: failed — ${expected.message}"
            } finally {
                scanningId = null
            }
        }
    }

    fun downloadAll() {
        statusMessage = null
        scope.launch {
            try {
                val dirsByRef = directories.associateByTo(mutableMapOf()) { it.folderRef }
                // Phase 1: discover + create subfolder directories for every (included) top-level folder.
                var created = 0
                for (root in directories.filter { it.topLevel && scannable(it) && !it.excluded }) {
                    scanningId = root.id
                    scanProgress = 0 to 0
                    created += createSubdirectories(root, dirsByRef)
                }
                // Phase 2: download each included directory's own importable files (top-levels + leaves).
                var downloaded = 0
                for (dir in dirsByRef.values.filter { scannable(it) && !it.excluded }) {
                    scanningId = dir.id
                    scanProgress = 0 to 0
                    downloaded += downloadFolder(dir).filesDownloaded
                }
                statusMessage = "Created $created new director${if (created == 1) "y" else "ies"}; downloaded $downloaded file(s)."
            } catch (expected: Exception) {
                statusMessage = "Download all failed — ${expected.message}"
            } finally {
                scanningId = null
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Import Directories", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = scanningId == null && directories.any(::scannable),
                    onClick = { downloadAll() },
                ) { Text("Download all") }
                Button(onClick = { showAddDialog = true }) { Text("Add directory") }
            }
        }

        Text(
            "\"Find subfolders\" on a top-level folder discovers its importable subfolders; download those to " +
                "fetch files, then use each row's import link. Tick \"Exclude\" to skip a folder.",
            style = MaterialTheme.typography.bodyMedium,
        )

        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

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
                    csvImports = csvImports,
                    qifImports = qifImports,
                    deviceId = deviceId,
                    indent = indent,
                    canDownload = importFileSourceFactory != null && scanningId == null,
                    isDownloading = scanningId == directory.id,
                    scanProgress = scanProgress,
                    onDownload = { downloadDirectory(directory) },
                    onToggleExclude = {
                        scope.launch { importEngine.updateImportDirectory(directory.copy(excluded = !directory.excluded)) }
                    },
                    onImport = onOpenImports,
                    onAudit = { onOpenAudit(directory) },
                    onDelete = { scope.launch { importEngine.deleteImportDirectory(directory.id) } },
                )
            }
        }
    }

    if (showAddDialog) {
        AddImportDirectoryDialog(
            driveFolderBrowser = driveFolderBrowser,
            onDismiss = { showAddDialog = false },
            onCreate = { name, provider, folderRef, displayPath ->
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

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun ImportDirectoryRow(
    directory: ImportDirectory,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImports: List<CsvImport>,
    qifImports: List<QifImport>,
    deviceId: DeviceId,
    indent: Dp,
    canDownload: Boolean,
    isDownloading: Boolean,
    scanProgress: Pair<Int, Int>,
    onDownload: () -> Unit,
    onToggleExclude: () -> Unit,
    onImport: (ImportTab) -> Unit,
    onAudit: () -> Unit,
    onDelete: () -> Unit,
) {
    val trackedFiles by importDirectoryRepository
        .getTrackedFiles(directory.id)
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

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
    // Top-level folders discover + create child directories; discovered subfolders download their files.
    val actionLabel = if (directory.topLevel) "Find subfolders" else "Download"

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canDownload && !onWrongDevice && !directory.excluded,
                    onClick = onDownload,
                ) { Text(if (isDownloading) "Working…" else actionLabel) }
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
