@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimport.API_DOWNLOAD_ALL_TASK_KEY
import com.moneymanager.apiimport.API_IMPORT_ALL_TASK_KEY
import com.moneymanager.apiimport.API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY
import com.moneymanager.apiimport.ApiConnectionsSnapshot
import com.moneymanager.apiimport.ApiDownloadOutcome
import com.moneymanager.apiimport.ApiImportListener
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.apiDownloadTaskKey
import com.moneymanager.apiimport.apiImportTaskKey
import com.moneymanager.apiimport.displaySummary
import com.moneymanager.apiimport.filterCredentialsByStrategy
import com.moneymanager.apiimport.loadApiConnectionsSnapshot
import com.moneymanager.apiimport.sessionImportedAtCurrentRevision
import com.moneymanager.apiimport.sessionsToImportInBulk
import com.moneymanager.apiimport.splitSessionsByImportState
import com.moneymanager.apiimport.startApiSessionsArchiveImport
import com.moneymanager.apiimport.storeNewSigningKey
import com.moneymanager.apiimporter.ApiCounterpartySuggestion
import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.ApiSessionDownloadResult
import com.moneymanager.apiimporter.ApiSessionImportResult
import com.moneymanager.apiimporter.ApiSessionsArchive
import com.moneymanager.apiimporter.displaySummary
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.formatElapsedTime
import com.moneymanager.tasks.isRunning
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.foundation.rememberPersistedState
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.util.ContentCopyIcon
import com.moneymanager.ui.util.ExposedDropdownMenu
import com.moneymanager.ui.util.displayDate
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Composable
fun ApiSessionsScreen(
    apiSessionRepository: ApiSessionReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    importRecordRepository: ImportRecordReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    maintenance: Maintenance,
    deviceId: DeviceId,
    apiImportService: ApiImportService,
    reimports: Reimports,
    onAddCredentialClick: () -> Unit = {},
    onApiStrategiesClick: () -> Unit = {},
    onSessionClick: (ApiSession) -> Unit = {},
    onTransactionsImported: () -> Unit = {},
) {
    val importEngine = LocalImportEngine.current
    val vault = LocalCredentialVault.current
    val vaultState by vault.state.collectAsState()
    val vaultBundle = (vaultState as? VaultState.Unlocked)?.bundle
    val scope = rememberSchemaAwareCoroutineScope()
    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()
    val clipboard = LocalClipboard.current
    val dateRangeBySession by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importTimelineRepository.getApiSessionDateRanges().map { ranges -> ranges.associateBy { it.fileId } }
    }

    var snapshot by remember { mutableStateOf(ApiConnectionsSnapshot()) }
    var selectedStrategyId by rememberPersistedState<ApiImportStrategyId?>(
        "apiSessions.strategy",
        ApiImportStrategyId.serializer().nullable,
    ) {
        null
    }
    var selectedTab by rememberPersistedState("apiSessions.tab", Int.serializer()) { 0 }
    var isLoading by remember { mutableStateOf(true) }
    // A saved strategy may have been deleted since, which would filter to an empty list under "All strategies".
    LaunchedEffect(snapshot.strategies, isLoading) {
        if (!isLoading && selectedStrategyId != null && snapshot.strategies.none { it.id == selectedStrategyId }) selectedStrategyId = null
    }
    // Per-session import state
    var importResultBySession by remember { mutableStateOf<Map<ApiSessionId, ApiSessionImportResult>>(emptyMap()) }
    var importErrorBySession by remember { mutableStateOf<Map<ApiSessionId, String>>(emptyMap()) }
    var importProgressBySession by remember { mutableStateOf<Map<ApiSessionId, Progress>>(emptyMap()) }
    // Sessions whose import was clicked but whose background task has not yet registered — the gap covers
    // the counterparty-discovery scan and the engine's initial index build, both of which can run for
    // seconds on a large database with no progress callback. Tracking it here lets the button grey out and
    // a progress indicator appear the instant Import is pressed, rather than only once real progress arrives.
    var preparingImportSessions by remember { mutableStateOf<Set<ApiSessionId>>(emptySet()) }

    // Per-credential download result state (cleared when a new download starts)
    var downloadResultByCredential by remember { mutableStateOf<Map<ApiCredentialId, ApiSessionDownloadResult>>(emptyMap()) }
    // Credentials whose last download was rejected as unauthorised: the card asks for a new token.
    var authFailureByCredential by remember { mutableStateOf<Map<ApiCredentialId, ApiDownloadOutcome.AuthFailed>>(emptyMap()) }

    // Credentials whose next download should ignore what earlier sessions already covered. Consumed by
    // that download, so the box visibly unticks rather than silently leaving every later download a
    // full re-sweep.
    var forceFullDownloadByCredential by remember { mutableStateOf<Set<ApiCredentialId>>(emptySet()) }
    var signingKeyErrorByCredential by remember { mutableStateOf<Map<ApiCredentialId, String>>(emptyMap()) }
    // Confirmations waiting for a person, shown one at a time: a card's import, or a bulk run waiting on [answer].
    var pendingImports by remember { mutableStateOf<List<PendingApiImport>>(emptyList()) }
    var pendingReimport by remember { mutableStateOf<PendingApiReimport?>(null) }
    var pendingReimportAll by remember { mutableStateOf<PendingApiReimportAll?>(null) }

    val apiReads =
        ApiImportReads(
            apiSessionRepository = apiSessionRepository,
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
            currencyRepository = currencyRepository,
            cryptoRepository = cryptoRepository,
            importRecordRepository = importRecordRepository,
            accountMappingRepository = accountMappingRepository,
        )

    fun refresh() {
        scope.launch {
            try {
                snapshot =
                    loadApiConnectionsSnapshot(
                        apiSessionRepository,
                        apiImportStrategyRepository,
                        deviceId,
                        apiImportService::transactionsBlockReason,
                    )
            } finally {
                isLoading = false
            }
        }
    }

    // Keeps the cards current while a download or import runs, whichever button started it.
    val importListener =
        ApiImportListener(
            onSessionCreated = { _, _ -> refresh() },
            onDownloaded = { credentialId, result ->
                downloadResultByCredential = downloadResultByCredential + (credentialId to result)
                refresh()
            },
            onImported = { sessionId, result ->
                importResultBySession = importResultBySession + (sessionId to result)
                refresh()
                onTransactionsImported()
            },
            confirmCounterparties = { attention ->
                val answer = CompletableDeferred<Map<String, String>?>()
                val pending = PendingApiImport(attention.session, attention.strategy, attention.counterparties, answer)
                pendingImports = pendingImports + pending
                try {
                    answer.await()
                } finally {
                    // A cancelled run takes its question with it.
                    pendingImports = pendingImports - pending
                }
            },
        )

    fun isImporting(sessionId: ApiSessionId): Boolean = tasks.isRunning(apiImportTaskKey(sessionId)) || sessionId in preparingImportSessions

    fun startImport(
        session: ApiSession,
        strategy: ApiImportStrategy,
        counterpartyAccountNames: Map<String, String>,
    ) {
        taskManager.start(
            key = apiImportTaskKey(session.id),
            title = "Import",
            initialDetail = "Starting import for session #${session.id}.",
        ) {
            val result = apiImportService.import(this, session.id, strategy, counterpartyAccountNames)
            importListener.onImported(session.id, result)
            result.displaySummary()
        }
    }

    fun startDownload(
        credential: ApiCredential,
        forceFull: Boolean,
    ) {
        if (taskManager.isRunning(apiDownloadTaskKey(credential.id))) return
        downloadResultByCredential = downloadResultByCredential - credential.id
        authFailureByCredential = authFailureByCredential - credential.id
        taskManager.start(
            key = apiDownloadTaskKey(credential.id),
            title = "Download",
            initialDetail = "Fetching credentials.",
        ) {
            val outcome = apiImportService.download(this, credential, forceFull, importListener)
            if (outcome is ApiDownloadOutcome.AuthFailed) authFailureByCredential = authFailureByCredential + (credential.id to outcome)
            outcome.message
        }
    }

    fun downloadAll(targets: List<ApiCredential>) {
        val forceFullIds = forceFullDownloadByCredential.intersect(targets.map { it.id }.toSet())
        forceFullDownloadByCredential = forceFullDownloadByCredential - forceFullIds
        scope.launch {
            // Unlock before starting the task, so declining the prompt doesn't leave a failed task behind.
            try {
                vault.requireUnlocked("Download all APIs")
            } catch (_: CredentialVaultLockedException) {
                return@launch
            }
            taskManager.start(
                key = API_DOWNLOAD_ALL_TASK_KEY,
                title = "Download all",
                initialDetail = "Downloading ${targets.size} API connection(s).",
            ) {
                apiImportService
                    .downloadAndImportAll(this, targets, forceFullIds, importListener, importSessions = false)
                    .summary()
            }
        }
    }

    fun importAll(targets: List<ApiCredential>) {
        taskManager.start(
            key = API_IMPORT_ALL_TASK_KEY,
            title = "Import all",
            initialDetail = "Importing the sessions of ${targets.size} API connection(s).",
        ) {
            apiImportService
                .downloadAndImportAll(this, targets, listener = importListener, downloadSessions = false)
                .summary()
        }
    }

    var archiveMessage by remember { mutableStateOf<String?>(null) }
    val exportSaver = rememberFileSaver(onResult = {})
    val importPicker =
        rememberFilePicker(
            mimeTypes = listOf("application/json"),
            onResult = { result ->
                if (result != null) {
                    archiveMessage = null
                    taskManager.startApiSessionsArchiveImport(
                        result.content,
                        deviceId,
                        apiSessionRepository,
                        apiImportStrategyRepository,
                        importEngine,
                    ) { summary ->
                        archiveMessage = summary
                        refresh()
                    }
                }
            },
        )

    LaunchedEffect(Unit) { refresh() }
    EnsureApiConnectionRows(
        apiImportService = apiImportService,
        strategies = snapshot.strategies,
        credentialRows = snapshot.credentials,
        onCreated = { refresh() },
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "API Sessions", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    // Built at click time: a snapshot taken when the session list changed would miss the
                    // requests a download records after its session appears.
                    scope.launch {
                        runCatchingCancellable { ApiSessionsArchive.export(apiSessionRepository, apiImportStrategyRepository) }
                            .onSuccess { exportSaver.launch("api-sessions.json", it) }
                            .onFailure { archiveMessage = "Failed to export API sessions: ${it.message}" }
                    }
                }) { Text("Export") }
                TextButton(
                    onClick = { importPicker.launch() },
                    enabled = !tasks.isRunning(API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY),
                ) { Text("Import") }
                TextButton(onClick = onApiStrategiesClick) { Text("Strategies") }
                TextButton(onClick = onAddCredentialClick) { Text("+ Connect") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        tasks.runningProgress(API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY)?.let {
            ApiReimportProgress(it)
            Spacer(modifier = Modifier.height(8.dp))
        }

        archiveMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (LocalApiDownloadNotice.current != null) {
            ApiDownloadNoticeBanner()
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (vaultBundle == null) {
            CredentialsLockedBanner(reason = "Show your API credentials")
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            snapshot.credentials.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No credentials yet. Click '+ Connect' to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                val filteredCredentials =
                    filterCredentialsByStrategy(
                        credentials = snapshot.credentials,
                        strategyIdByCredential = snapshot.infoByCredential.mapValues { it.value.strategy?.id },
                        selectedStrategyId = selectedStrategyId,
                    )
                val split =
                    splitSessionsByImportState(
                        credentials = filteredCredentials,
                        sessionsByCredential = snapshot.sessionsByCredential,
                        currentStrategyRevisionByCredential = snapshot.infoByCredential.mapValues { it.value.currentRevision },
                        importedSessionRevisions = snapshot.importedSessionRevisions,
                    )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StrategyFilterDropdown(
                        strategies = snapshot.strategies,
                        selectedStrategyId = selectedStrategyId,
                        onStrategySelected = { selectedStrategyId = it },
                        modifier = Modifier.weight(1f),
                    )
                    // Both follow the strategy filter, so they can refresh just one provider's sessions.
                    Button(
                        onClick = { downloadAll(filteredCredentials) },
                        enabled = !tasks.isRunning(API_DOWNLOAD_ALL_TASK_KEY),
                    ) {
                        Text("Download all (${filteredCredentials.size})")
                    }
                    // What "Import all" would import: sessions never imported. One imported under an older
                    // strategy revision is left for its card's re-import.
                    val importableCount =
                        sessionsToImportInBulk(
                            sessions = filteredCredentials.flatMap { snapshot.sessionsByCredential[it.id].orEmpty() },
                            credentialIds = filteredCredentials.filter { it.strategyId != null }.map { it.id }.toSet(),
                            everImportedSessionIds = snapshot.everImportedSessionIds,
                        ).size
                    Button(
                        onClick = { importAll(filteredCredentials) },
                        enabled = importableCount > 0 && !tasks.isRunning(API_IMPORT_ALL_TASK_KEY),
                    ) {
                        Text("Import all ($importableCount)")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Outstanding (${split.outstandingCount})") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Imported (${split.importedCount})") },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // The Outstanding tab is the working tab: credential cards stay visible even with no
                // outstanding sessions so Download remains reachable. The Imported tab only lists
                // credentials that actually have imported sessions.
                val visibleCredentials =
                    if (selectedTab == 0) {
                        filteredCredentials
                    } else {
                        filteredCredentials.filter { split.importedByCredential[it.id].orEmpty().isNotEmpty() }
                    }

                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    if (visibleCredentials.isEmpty()) {
                        Text(
                            text =
                                if (selectedTab == 0) {
                                    "No credentials for this strategy. Click '+ Connect' to add one."
                                } else {
                                    "No imported sessions yet."
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visibleCredentials) { credential ->
                            val credentialSessions =
                                if (selectedTab == 0) {
                                    split.outstandingByCredential[credential.id].orEmpty()
                                } else {
                                    split.importedByCredential[credential.id].orEmpty()
                                }
                            val info = snapshot.infoByCredential[credential.id]
                            val isDownloading = tasks.isRunning(apiDownloadTaskKey(credential.id))
                            CredentialCard(
                                credential = credential,
                                dateRangeBySession = dateRangeBySession,
                                providerLabel = info?.strategyName,
                                requiresSigning = info?.requiresSigning == true,
                                transactionsBlockReason = info?.transactionsBlockReason,
                                secrets = info?.strategyName?.let { vaultBundle?.apiCredential(it) },
                                signingKeyError = signingKeyErrorByCredential[credential.id],
                                onGenerateSigningKey = {
                                    signingKeyErrorByCredential = signingKeyErrorByCredential - credential.id
                                    scope.launch {
                                        val name = info?.strategyName ?: return@launch
                                        val error = vault.storeNewSigningKey(name)
                                        if (error == null) {
                                            refresh()
                                        } else {
                                            signingKeyErrorByCredential = signingKeyErrorByCredential + (credential.id to error)
                                        }
                                    }
                                },
                                onCopyText = { text -> scope.launch { clipboard.setPlainText(text) } },
                                sessions = credentialSessions,
                                isDownloading = isDownloading,
                                downloadResult = downloadResultByCredential[credential.id],
                                authFailure = authFailureByCredential[credential.id],
                                strategy = info?.strategy,
                                otherTokens =
                                    vaultBundle
                                        ?.apiCredentials
                                        .orEmpty()
                                        .filter {
                                            it.strategyName != info?.strategyName
                                        }.map { it.token }
                                        .toSet(),
                                onTokenSaved = { startDownload(credential, false) },
                                downloadProgress = tasks.runningProgress(apiDownloadTaskKey(credential.id)),
                                importResultBySession = importResultBySession,
                                importErrorBySession = importErrorBySession,
                                // A running import reports through its task; before that, the discovery scan
                                // reports here.
                                importProgressBySession =
                                    importProgressBySession +
                                        credentialSessions.mapNotNull { session ->
                                            tasks.runningProgress(apiImportTaskKey(session.id))?.let { session.id to it }
                                        },
                                importedSessionRevisions = snapshot.importedSessionRevisions,
                                selectedStrategyRevision = info?.currentRevision,
                                isImportingSession = ::isImporting,
                                hasEverBeenImported = { sessionId -> sessionId in snapshot.everImportedSessionIds },
                                importedSessionCount =
                                    snapshot.sessionsByCredential[credential.id].orEmpty().count {
                                        it.id in snapshot.everImportedSessionIds
                                    },
                                isAnySessionImporting =
                                    snapshot.sessionsByCredential[credential.id].orEmpty().any { isImporting(it.id) },
                                forceFullDownload = credential.id in forceFullDownloadByCredential,
                                onForceFullDownloadChange = { checked ->
                                    forceFullDownloadByCredential =
                                        if (checked) {
                                            forceFullDownloadByCredential + credential.id
                                        } else {
                                            forceFullDownloadByCredential - credential.id
                                        }
                                },
                                onDownload = {
                                    val forceFull = credential.id in forceFullDownloadByCredential
                                    forceFullDownloadByCredential = forceFullDownloadByCredential - credential.id
                                    startDownload(credential, forceFull)
                                },
                                onImport = { session ->
                                    importResultBySession = importResultBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    // Mark it importing synchronously so the button greys and a progress
                                    // indicator appears immediately, before the discovery scan below runs.
                                    preparingImportSessions = preparingImportSessions + session.id
                                    scope.launch {
                                        try {
                                            val strategy =
                                                apiImportService.resolveStrategy(credential) ?: run {
                                                    importErrorBySession =
                                                        importErrorBySession + (session.id to "No import strategy configured")
                                                    return@launch
                                                }
                                            // Safe for all providers: returns empty when the strategy has no
                                            // counterparty field, skipping the confirmation dialog. Its
                                            // sub-step progress surfaces on the same bar as the import phases.
                                            val suggestions =
                                                apiImportService.counterpartiesToConfirm(
                                                    sessionId = session.id,
                                                    strategy = strategy,
                                                    onProgress = { progress ->
                                                        importProgressBySession = importProgressBySession + (session.id to progress)
                                                    },
                                                )
                                            if (suggestions.isEmpty()) {
                                                // startImport registers the task synchronously, so
                                                // isImportingSession and its progress carry across the handoff.
                                                startImport(session, strategy, emptyMap())
                                            } else {
                                                pendingImports = pendingImports +
                                                    PendingApiImport(
                                                        session = session,
                                                        strategy = strategy,
                                                        counterparties = suggestions,
                                                    )
                                            }
                                        } finally {
                                            preparingImportSessions = preparingImportSessions - session.id
                                            importProgressBySession = importProgressBySession - session.id
                                        }
                                    }
                                },
                                onReimport = { session ->
                                    scope.launch {
                                        val strategy =
                                            apiImportService.resolveStrategy(credential) ?: run {
                                                importErrorBySession =
                                                    importErrorBySession + (session.id to "No import strategy configured")
                                                return@launch
                                            }
                                        pendingReimport = PendingApiReimport(session = session, strategy = strategy)
                                    }
                                },
                                onReimportAll = { importedCount ->
                                    scope.launch {
                                        val strategy = apiImportService.resolveStrategy(credential) ?: return@launch
                                        pendingReimportAll =
                                            PendingApiReimportAll(
                                                credentialId = credential.id,
                                                strategy = strategy,
                                                importedSessionCount = importedCount,
                                            )
                                    }
                                },
                                onSessionClick = onSessionClick,
                                onCopyError = { error -> scope.launch { clipboard.setPlainText(error) } },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    pendingImports.firstOrNull()?.let { import ->
        // Keyed, so the next question opens with its own names rather than the last one's edits.
        key(import) {
            CounterpartyConfirmationDialog(
                counterparties = import.counterparties,
                onDismiss = {
                    pendingImports = pendingImports - import
                    import.answer?.complete(null)
                },
                onConfirm = { namesByCounterpartyId ->
                    pendingImports = pendingImports - import
                    val answer = import.answer
                    if (answer != null) {
                        answer.complete(namesByCounterpartyId)
                    } else {
                        startImport(
                            session = import.session,
                            strategy = import.strategy,
                            counterpartyAccountNames = namesByCounterpartyId,
                        )
                    }
                },
            )
        }
    }

    pendingReimport?.let { reimport ->
        ApiReimportDialog(
            session = reimport.session,
            strategy = reimport.strategy,
            apiReads = apiReads,
            reimports = reimports,
            maintenance = maintenance,
            importEngine = importEngine,
            onDismiss = { pendingReimport = null },
            onComplete = { result ->
                pendingReimport = null
                importResultBySession =
                    importResultBySession +
                    (
                        reimport.session.id to
                            ApiSessionImportResult(
                                accountCount = 0,
                                transactionCount = result.rerun.transactionsImported + result.rerun.tradesImported,
                            )
                    )
                refresh()
                onTransactionsImported()
            },
        )
    }

    pendingReimportAll?.let { reimportAll ->
        ApiReimportAllDialog(
            credentialId = reimportAll.credentialId,
            importedSessionCount = reimportAll.importedSessionCount,
            strategy = reimportAll.strategy,
            apiReads = apiReads,
            reimports = reimports,
            maintenance = maintenance,
            importEngine = importEngine,
            onDismiss = { pendingReimportAll = null },
            onComplete = {
                pendingReimportAll = null
                refresh()
                onTransactionsImported()
            },
        )
    }
}

@Composable
private fun StrategyFilterDropdown(
    strategies: List<ApiImportStrategy>,
    selectedStrategyId: ApiImportStrategyId?,
    onStrategySelected: (ApiImportStrategyId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = strategies.firstOrNull { it.id == selectedStrategyId }?.name ?: ALL_STRATEGIES_LABEL
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Strategy") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(ALL_STRATEGIES_LABEL) },
                onClick = {
                    onStrategySelected(null)
                    expanded = false
                },
            )
            strategies.forEach { strategy ->
                DropdownMenuItem(
                    text = { Text(strategy.name) },
                    onClick = {
                        onStrategySelected(strategy.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val ALL_STRATEGIES_LABEL = "All strategies"

private fun List<TaskState>.runningProgress(key: String): Progress? = firstOrNull { it.key == key && it.isRunning }?.progress
