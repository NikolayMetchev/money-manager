@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionKind
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.ui.background.LocalBackgroundTaskManager
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.monzo.MonzoAccountsDownloadResult
import com.moneymanager.ui.monzo.MonzoDownloadProgress
import com.moneymanager.ui.monzo.MonzoDownloadResult
import com.moneymanager.ui.monzo.MonzoImportResult
import com.moneymanager.ui.monzo.downloadMonzoAccounts
import com.moneymanager.ui.monzo.downloadMonzoTransactions
import com.moneymanager.ui.monzo.importMonzoSessionTransactions
import com.moneymanager.ui.util.displayDateTime
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = logging()

@Composable
@Suppress("DEPRECATION")
fun ApiSessionsScreen(
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
    onMonzoConnectClick: () -> Unit = {},
    onSessionClick: (ApiSession) -> Unit = {},
    onTransactionsImported: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val backgroundTasks = LocalBackgroundTaskManager.current
    val clipboardManager = LocalClipboardManager.current

    var credentials by remember { mutableStateOf<List<MonzoCredential>>(emptyList()) }
    var sessionsByCredential by remember { mutableStateOf<Map<MonzoCredentialId, List<ApiSession>>>(emptyMap()) }
    var importedSessionIds by remember { mutableStateOf<Set<ApiSessionId>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var sessionToRevoke by remember { mutableStateOf<ApiSession?>(null) }

    // Per-session import state
    var importResultBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoImportResult>>(emptyMap()) }
    var importErrorBySession by remember { mutableStateOf<Map<ApiSessionId, String>>(emptyMap()) }

    // Per-credential download result state (cleared when a new download starts)
    var accountsDownloadResultByCredential by remember { mutableStateOf<Map<MonzoCredentialId, MonzoAccountsDownloadResult>>(emptyMap()) }
    var downloadResultByCredential by remember { mutableStateOf<Map<MonzoCredentialId, MonzoDownloadResult>>(emptyMap()) }
    var downloadProgressByCredential by remember { mutableStateOf<Map<MonzoCredentialId, MonzoDownloadProgress?>>(emptyMap()) }

    fun refresh() {
        scope.launch {
            try {
                val allCredentials = apiSessionRepository.getAllCredentials()
                val allSessions = apiSessionRepository.getSessionsByDevice(deviceId)
                credentials = allCredentials
                sessionsByCredential =
                    allSessions
                        .filter { it.credentialId != null }
                        .groupBy { it.credentialId!! }
                importedSessionIds = apiSessionRepository.getImportedSessionIds()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    sessionToRevoke?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToRevoke = null },
            title = { Text("Revoke Session?") },
            text = {
                Text("This will revoke the session created on ${session.createdAt.displayDateTime()}.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToRevoke = null
                        scope.launch {
                            try {
                                apiSessionRepository.revokeSession(session.id, Clock.System.now())
                                refresh()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to revoke session: ${expected.message}" }
                            }
                        }
                    },
                ) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRevoke = null }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "API Sessions", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onMonzoConnectClick) { Text("+ Connect") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            credentials.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No credentials yet. Click '+ Connect' to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(credentials) { credential ->
                            val credentialSessions = sessionsByCredential[credential.id].orEmpty()
                            val isDownloadingAccounts = backgroundTasks.isRunning(monzoAccountsDownloadTaskKey(credential.id))
                            val isDownloadingTransactions = backgroundTasks.isRunning(monzoTransactionsDownloadTaskKey(credential.id))
                            val isCredentialBusy = isDownloadingAccounts || isDownloadingTransactions

                            CredentialCard(
                                credential = credential,
                                sessions = credentialSessions,
                                isDownloadingAccounts = isDownloadingAccounts,
                                isDownloadingTransactions = isDownloadingTransactions,
                                accountsDownloadResult = accountsDownloadResultByCredential[credential.id],
                                downloadResult = downloadResultByCredential[credential.id],
                                downloadProgress = downloadProgressByCredential[credential.id],
                                importResultBySession = importResultBySession,
                                importErrorBySession = importErrorBySession,
                                importedSessionIds = importedSessionIds,
                                isImportingSession = { sessionId -> backgroundTasks.isRunning(monzoImportTaskKey(sessionId)) },
                                onDownloadAccounts = {
                                    accountsDownloadResultByCredential = accountsDownloadResultByCredential - credential.id
                                    scope.launch {
                                        val newSessionId =
                                            apiSessionRepository.createSession(
                                                token = credential.token,
                                                deviceId = deviceId,
                                                createdAt = Clock.System.now(),
                                                expiresAt = null,
                                                credentialId = credential.id,
                                                kind = ApiSessionKind.ACCOUNTS,
                                            )
                                        refresh()
                                        backgroundTasks.startTask(
                                            key = monzoAccountsDownloadTaskKey(credential.id),
                                            title = "Download Accounts",
                                            initialDetail = "Starting accounts download for session #$newSessionId.",
                                        ) {
                                            val result =
                                                downloadMonzoAccounts(
                                                    token = credential.token,
                                                    apiClient =
                                                        createApiClient(
                                                            trafficRecorder =
                                                                ApiSessionTrafficRecorder(
                                                                    sessionId = newSessionId,
                                                                    apiSessionRepository = apiSessionRepository,
                                                                ),
                                                            engine = null,
                                                        ),
                                                    apiSessionRepository = apiSessionRepository,
                                                    sessionId = newSessionId,
                                                )
                                            accountsDownloadResultByCredential =
                                                accountsDownloadResultByCredential + (credential.id to result)
                                            result.displaySummary()
                                        }
                                    }
                                },
                                onDownloadTransactions = {
                                    downloadResultByCredential = downloadResultByCredential - credential.id
                                    downloadProgressByCredential = downloadProgressByCredential - credential.id
                                    val accountsSession = credentialSessions.firstOrNull { it.kind == ApiSessionKind.ACCOUNTS }
                                    scope.launch {
                                        val newSessionId =
                                            apiSessionRepository.createSession(
                                                token = credential.token,
                                                deviceId = deviceId,
                                                createdAt = Clock.System.now(),
                                                expiresAt = null,
                                                credentialId = credential.id,
                                                kind = ApiSessionKind.TRANSACTIONS,
                                            )
                                        refresh()
                                        backgroundTasks.startTask(
                                            key = monzoTransactionsDownloadTaskKey(credential.id),
                                            title = "Download Transactions",
                                            initialDetail = "Starting transactions download for session #$newSessionId.",
                                        ) {
                                            val result =
                                                downloadMonzoTransactions(
                                                    token = credential.token,
                                                    apiClient =
                                                        createApiClient(
                                                            trafficRecorder =
                                                                ApiSessionTrafficRecorder(
                                                                    sessionId = newSessionId,
                                                                    apiSessionRepository = apiSessionRepository,
                                                                ),
                                                            engine = null,
                                                        ),
                                                    apiSessionRepository = apiSessionRepository,
                                                    sessionId = newSessionId,
                                                    accountsSessionId = accountsSession?.id,
                                                    onProgress = { progress ->
                                                        downloadProgressByCredential =
                                                            downloadProgressByCredential + (credential.id to progress)
                                                        update(progress.downloadDetail())
                                                    },
                                                )
                                            downloadResultByCredential = downloadResultByCredential + (credential.id to result)
                                            downloadProgressByCredential = downloadProgressByCredential - credential.id
                                            result.displaySummary()
                                        }
                                    }
                                },
                                onImport = { session ->
                                    importResultBySession = importResultBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    val accountsSession =
                                        if (session.kind == ApiSessionKind.TRANSACTIONS) {
                                            credentialSessions.firstOrNull { it.kind == ApiSessionKind.ACCOUNTS }
                                        } else {
                                            null
                                        }
                                    val importLabel =
                                        if (session.kind ==
                                            ApiSessionKind.ACCOUNTS
                                        ) {
                                            "Import Accounts"
                                        } else {
                                            "Import Transactions"
                                        }
                                    backgroundTasks.startTask(
                                        key = monzoImportTaskKey(session.id),
                                        title = importLabel,
                                        initialDetail = "Starting $importLabel for session #${session.id}.",
                                    ) {
                                        val result =
                                            importMonzoSessionTransactions(
                                                apiSessionRepository = apiSessionRepository,
                                                accountRepository = accountRepository,
                                                currencyRepository = currencyRepository,
                                                transactionRepository = transactionRepository,
                                                transferSourceQueries = transferSourceQueries,
                                                entitySourceQueries = entitySourceQueries,
                                                personRepository = personRepository,
                                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                                deviceId = deviceId,
                                                sessionId = session.id,
                                                accountsSessionId = accountsSession?.id,
                                                onProgress = ::update,
                                            )
                                        apiSessionRepository.markSessionImported(session.id, Clock.System.now())
                                        maintenanceService.refreshMaterializedViews()
                                        importResultBySession = importResultBySession + (session.id to result)
                                        refresh()
                                        onTransactionsImported()
                                        result.displaySummary()
                                    }
                                },
                                onSessionClick = onSessionClick,
                                onRevokeSession = { sessionToRevoke = it },
                                onCopyError = { error -> clipboardManager.setText(AnnotatedString(error)) },
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
}

@Composable
private fun CredentialCard(
    credential: MonzoCredential,
    sessions: List<ApiSession>,
    isDownloadingAccounts: Boolean,
    isDownloadingTransactions: Boolean,
    accountsDownloadResult: MonzoAccountsDownloadResult?,
    downloadResult: MonzoDownloadResult?,
    downloadProgress: MonzoDownloadProgress?,
    importResultBySession: Map<ApiSessionId, MonzoImportResult>,
    importErrorBySession: Map<ApiSessionId, String>,
    importedSessionIds: Set<ApiSessionId>,
    isImportingSession: (ApiSessionId) -> Boolean,
    onDownloadAccounts: () -> Unit,
    onDownloadTransactions: () -> Unit,
    onImport: (ApiSession) -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onRevokeSession: (ApiSession) -> Unit,
    onCopyError: (String) -> Unit,
) {
    val displayToken =
        if (credential.token.length > 16) {
            "${credential.token.take(8)}...${credential.token.takeLast(8)}"
        } else {
            credential.token
        }
    val isCredentialBusy = isDownloadingAccounts || isDownloadingTransactions

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    "Credential · " +
                        credential.type.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = displayToken,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Added: ${credential.createdAt.displayDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            accountsDownloadResult?.let {
                Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            downloadResult?.let {
                Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (isDownloadingAccounts) {
                Text(
                    text = "Downloading accounts...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isDownloadingTransactions) {
                Text(
                    text =
                        if (downloadProgress == null) {
                            "Preparing download..."
                        } else {
                            "Downloading account ${downloadProgress.accountIndex}/${downloadProgress.accountCount}, " +
                                "page ${downloadProgress.page}. ${downloadProgress.downloadedResponsePageCount} response(s) so far."
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownloadAccounts, enabled = !isCredentialBusy, modifier = Modifier.weight(1f)) {
                    if (isDownloadingAccounts) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download Accounts")
                    }
                }
                Button(onClick = onDownloadTransactions, enabled = !isCredentialBusy, modifier = Modifier.weight(1f)) {
                    if (isDownloadingTransactions) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download Transactions")
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                HorizontalDivider()
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        isImporting = isImportingSession(session.id),
                        isAlreadyImported = session.id in importedSessionIds,
                        importResult = importResultBySession[session.id],
                        importError = importErrorBySession[session.id],
                        onImport = { onImport(session) },
                        onOpenTraffic = { onSessionClick(session) },
                        onRevoke = { onRevokeSession(session) },
                        onCopyError = onCopyError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ApiSession,
    isImporting: Boolean,
    isAlreadyImported: Boolean,
    importResult: MonzoImportResult?,
    importError: String?,
    onImport: () -> Unit,
    onOpenTraffic: () -> Unit,
    onRevoke: () -> Unit,
    onCopyError: (String) -> Unit,
) {
    val isActive =
        session.revokedAt == null &&
            (session.expiresAt?.let { it > Clock.System.now() } ?: true)

    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(containerColor, MaterialTheme.shapes.small)
                .clickable(onClick = onOpenTraffic)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val kindLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Accounts session"
                    ApiSessionKind.TRANSACTIONS -> "Transactions session"
                    null -> "Session"
                }
            Text(text = kindLabel, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isAlreadyImported) {
                    ImportedBadge()
                }
                SessionStatusBadge(isActive = isActive)
            }
        }

        Text(
            text = "Created: ${session.createdAt.displayDateTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        session.revokedAt?.let {
            Text(
                text = "Revoked: ${it.displayDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        importResult?.let {
            Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (isImporting) {
            val importLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Importing accounts..."
                    ApiSessionKind.TRANSACTIONS -> "Importing transactions..."
                    null -> "Importing..."
                }
            Text(text = importLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        importError?.let { error ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopyError(error) }) { Text("Copy") }
            }
        }

        if (isActive) {
            val importButtonLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Import Accounts"
                    ApiSessionKind.TRANSACTIONS -> "Import Transactions"
                    null -> "Import Transactions"
                }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onImport,
                    enabled = !isImporting && !isAlreadyImported,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(importButtonLabel)
                    }
                }
                OutlinedButton(onClick = onOpenTraffic) { Text("Traffic") }
                OutlinedButton(onClick = onRevoke) { Text("Revoke") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTraffic) { Text("Traffic") }
            }
        }
    }
}

@Composable
private fun ImportedBadge() {
    Box(
        modifier =
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Imported",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun SessionStatusBadge(isActive: Boolean) {
    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Box(
        modifier =
            Modifier
                .background(
                    color = containerColor,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isActive) "Active" else "Inactive",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
fun ApiSessionTrafficScreen(
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    highlightRequestId: ApiRequestId? = null,
    highlightJsonPath: String? = null,
    onBack: () -> Unit,
) {
    var session by remember { mutableStateOf<ApiSession?>(null) }
    var requests by remember { mutableStateOf<List<ApiRequest>>(emptyList()) }
    var responses by remember { mutableStateOf<List<ApiResponse>>(emptyList()) }
    var responseTransactionsByResponseId by remember { mutableStateOf<Map<ApiResponseId, List<ApiResponseTransaction>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    val pairs = remember(requests, responses) { pairRequestsAndResponses(requests, responses) }
    val pairsList = remember(pairs) { pairs.values.toList() }

    LaunchedEffect(sessionId) {
        try {
            session = apiSessionRepository.getSessionById(sessionId)
            requests = apiSessionRepository.getRequestsBySession(sessionId)
            responses = apiSessionRepository.getResponsesBySession(sessionId)
            responseTransactionsByResponseId =
                apiSessionRepository
                    .getResponseTransactionsBySession(sessionId)
                    .groupBy { responseTransaction -> responseTransaction.responseId }
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "API Traffic",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text =
                        session?.let { "${it.type.name.lowercase().replaceFirstChar { char -> char.uppercase() }} session #${it.id}" }
                            ?: "Session #$sessionId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            pairs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No requests or responses recorded yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()
                // Absolute Y (in root coordinates) of the highlighted JSON node, reported
                // by JsonTreeNode once it is laid out, so we can center it in the viewport.
                var highlightNodeRootY by remember(highlightJsonPath) { mutableFloatStateOf(-1f) }

                // Find and scroll to the pair that contains the highlighted response
                val highlightedPairIndex =
                    remember(highlightRequestId, highlightJsonPath, pairs, responseTransactionsByResponseId) {
                        if (highlightRequestId == null && highlightJsonPath == null) {
                            -1
                        } else {
                            // +1 accounts for the summary item at index 0
                            // First try to match by jsonPath in response transactions (for transaction sources).
                            // Use prefix matching so sub-paths (e.g. $.transactions[0].counterparty) still
                            // resolve to the response transaction at $.transactions[0].
                            val jsonPathMatchIndex =
                                if (highlightJsonPath != null) {
                                    pairsList.indexOfFirst { pair ->
                                        pair.response != null &&
                                            (highlightRequestId == null || pair.request?.id == highlightRequestId) &&
                                            responseTransactionsByResponseId[pair.response.id]
                                                ?.any { highlightJsonPath.startsWithJsonPath(it.jsonPath.value) } == true
                                    }
                                } else {
                                    -1
                                }
                            // Fall back to matching by requestId alone (for entity/account sources)
                            val highlightedIndex =
                                if (jsonPathMatchIndex >= 0) {
                                    jsonPathMatchIndex
                                } else if (highlightRequestId != null) {
                                    pairs[highlightRequestId]?.let { pairsList.indexOf(it) } ?: -1
                                } else {
                                    -1
                                }
                            if (highlightedIndex >= 0) highlightedIndex + 1 else -1
                        }
                    }

                LaunchedEffect(highlightedPairIndex) {
                    if (highlightedPairIndex >= 0) {
                        lazyListState.animateScrollToItem(highlightedPairIndex)
                        // Wait for the highlighted node's position to be reported, then
                        // do a second scroll to center it vertically in the viewport.
                        snapshotFlow { highlightNodeRootY }
                            .collect { nodeY ->
                                if (nodeY >= 0f) {
                                    val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                                    val listStartY = lazyListState.layoutInfo.viewportStartOffset.toFloat()
                                    val nodeOffsetInViewport = nodeY - listStartY
                                    val delta = nodeOffsetInViewport - viewportHeight / 2f
                                    lazyListState.scroll { scrollBy(delta) }
                                    return@collect
                                }
                            }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text = "${requests.size} request(s), ${responses.size} response(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(pairsList) { pair ->
                            val responseTransactions =
                                pair.response?.let { responseTransactionsByResponseId[it.id] }
                                    ?: emptyList()
                            val requestMatches = highlightRequestId == null || pair.request?.id == highlightRequestId
                            val isHighlighted =
                                (highlightRequestId != null || highlightJsonPath != null) &&
                                    requestMatches &&
                                    (
                                        // Highlight by specific jsonPath when available (transaction sources).
                                        // Use prefix matching so sub-paths resolve to the containing response.
                                        (
                                            highlightJsonPath != null &&
                                                responseTransactions.any {
                                                    highlightJsonPath.startsWithJsonPath(it.jsonPath.value)
                                                }
                                        ) ||
                                            // Highlight by requestId alone when no jsonPath match (entity/account sources)
                                            (highlightJsonPath == null && highlightRequestId != null)
                                    )
                            ApiTrafficPairCard(
                                pair = pair,
                                responseTransactions = responseTransactions,
                                highlightJsonPath = if (isHighlighted) highlightJsonPath else null,
                                onHighlightPositioned = if (isHighlighted) { y -> highlightNodeRootY = y } else null,
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
}

@Composable
private fun ApiTrafficPairCard(
    pair: ApiTrafficPair,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val isHighlighted = highlightJsonPath != null
    val cardContainerColor =
        if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pair.request?.let { request ->
                RequestTrafficItem(
                    title = "Request #${request.id}",
                    timestamp = request.requestedAt.displayDateTime(),
                    body = request.displayBody(),
                )
            } ?: MissingTrafficItem(label = "Request")

            HorizontalDivider()

            pair.response?.let { response ->
                ResponseTrafficItem(
                    title = "Response #${response.id}",
                    timestamp = response.respondedAt.displayDateTime(),
                    json = response.json,
                    responseTransactions = responseTransactions,
                    highlightJsonPath = highlightJsonPath,
                    onHighlightPositioned = onHighlightPositioned,
                )
            } ?: MissingTrafficItem(label = "Response")
        }
    }
}

@Composable
private fun MissingTrafficItem(label: String) {
    Text(
        text = "$label not recorded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RequestTrafficItem(
    title: String,
    timestamp: String,
    body: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ).padding(8.dp),
    ) {
        TrafficItemHeader(
            title = title,
            timestamp = timestamp,
        )
        SelectionContainer {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResponseTrafficItem(
    title: String,
    timestamp: String,
    json: String,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ).padding(8.dp),
    ) {
        TrafficItemHeader(
            title = title,
            timestamp = timestamp,
        )
        JsonViewer(
            json = json,
            responseTransactions = responseTransactions,
            highlightJsonPath = highlightJsonPath,
            onHighlightPositioned = onHighlightPositioned,
        )
    }
}

@Composable
private fun TrafficItemHeader(
    title: String,
    timestamp: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JsonViewer(
    json: String,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val jsonElement =
        remember(json) {
            runCatching { Json.parseToJsonElement(json) }.getOrNull()
        }

    if (jsonElement == null) {
        SelectionContainer {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        // Parse the highlight path into segments (e.g. "$.transactions[0]" → ["transactions", "0"])
        val highlightSegments =
            remember(highlightJsonPath) {
                parseJsonPathSegments(highlightJsonPath)
            }
        val latestTransactionByJsonPath =
            remember(responseTransactions) {
                responseTransactions
                    .groupBy { it.jsonPath.value }
                    .mapValues { (_, transactions) ->
                        transactions.maxBy { it.id.id }
                    }
            }
        Column {
            JsonTreeNode(
                label = null,
                element = jsonElement,
                jsonPath = "$",
                depth = 0,
                latestTransactionByJsonPath = latestTransactionByJsonPath,
                remainingHighlightSegments = highlightSegments,
                onHighlightPositioned = onHighlightPositioned,
            )
        }
    }
}

/**
 * Returns true if this path equals [prefix] or is a child of it
 * (e.g. "$.transactions[0].counterparty".startsWithJsonPath("$.transactions[0]") == true).
 */
private fun String.startsWithJsonPath(prefix: String): Boolean =
    this == prefix || this.startsWith("$prefix.") || this.startsWith("$prefix[")

/**
 * Converts a simple JSONPath (e.g. "$.transactions[2]") into a list of
 * string segments (["transactions", "2"]) used to trace the highlight path
 * through the JSON tree.  Returns null when no highlight is needed.
 */
private fun parseJsonPathSegments(jsonPath: String?): List<String>? {
    if (jsonPath == null) return null
    // Strip leading "$." prefix, then split on "." and "[…]"
    val stripped = jsonPath.removePrefix("$.").removePrefix("$")
    if (stripped.isEmpty()) return null

    val segments = mutableListOf<String>()
    // Split on "." first, then strip "[index]" from each part
    for (part in stripped.split(".")) {
        val bracketStart = part.indexOf('[')
        if (bracketStart < 0) {
            segments.add(part)
        } else {
            if (bracketStart > 0) segments.add(part.substring(0, bracketStart))
            val bracketEnd = part.indexOf(']', bracketStart)
            if (bracketEnd > bracketStart) {
                segments.add(part.substring(bracketStart + 1, bracketEnd))
            }
        }
    }
    return segments.ifEmpty { null }
}

@Composable
private fun JsonTreeNode(
    label: String?,
    element: JsonElement,
    jsonPath: String,
    depth: Int,
    latestTransactionByJsonPath: Map<String, ApiResponseTransaction> = emptyMap(),
    remainingHighlightSegments: List<String>? = null,
    forceExpandSubtree: Boolean = false,
    highlightSubtree: Boolean = false,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val childCount = element.childCount()
    val expandable = childCount > 0
    // This node is the target if all highlight segments have been consumed
    val isHighlightTarget = remainingHighlightSegments != null && remainingHighlightSegments.isEmpty()
    // Force-expand the node if it's on the highlight path
    val forceExpandPath = remainingHighlightSegments != null && remainingHighlightSegments.isNotEmpty()
    val shouldForceExpand = forceExpandSubtree || forceExpandPath || isHighlightTarget
    var expanded by remember(label, element, remainingHighlightSegments, forceExpandSubtree) {
        mutableStateOf(depth == 0 || shouldForceExpand)
    }

    val prefix =
        if (expandable) {
            if (expanded) "- " else "+ "
        } else {
            "  "
        }
    val line =
        buildString {
            append(prefix)
            if (label != null) {
                append(label)
                append(": ")
            }
            append(element.summary(expanded))
        }

    val nodeTransaction = latestTransactionByJsonPath[jsonPath]
    val isInHighlightedSubtree = highlightSubtree || isHighlightTarget
    val highlightBackground =
        when {
            isHighlightTarget -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
            highlightSubtree -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            nodeTransaction != null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    val highlightTextColor =
        if (isInHighlightedSubtree) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val lineModifier =
        Modifier
            .fillMaxWidth()
            .background(highlightBackground, MaterialTheme.shapes.extraSmall)
            .padding(start = (depth * 12).dp)
            .then(
                if (isHighlightTarget && onHighlightPositioned != null) {
                    Modifier.onGloballyPositioned { coords ->
                        onHighlightPositioned(coords.positionInRoot().y)
                    }
                } else {
                    Modifier
                },
            ).then(
                if (expandable) {
                    Modifier.clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            )

    Row(
        modifier = lineModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = highlightTextColor,
            modifier = Modifier.weight(1f),
        )
        nodeTransaction?.let { transaction ->
            JsonTransactionStatus(transaction = transaction)
        }
    }
    if (expanded) {
        when (element) {
            is JsonObject -> {
                element.entries.forEach { (key, value) ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments == null -> null
                            remainingHighlightSegments.isEmpty() -> null
                            remainingHighlightSegments.first() == key -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "\"$key\"",
                        element = value,
                        jsonPath = jsonObjectChildPath(jsonPath, key),
                        depth = depth + 1,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = isInHighlightedSubtree,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, value ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments == null -> null
                            remainingHighlightSegments.isEmpty() -> null
                            remainingHighlightSegments.first() == index.toString() -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "[$index]",
                        element = value,
                        jsonPath = "$jsonPath[$index]",
                        depth = depth + 1,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = isInHighlightedSubtree,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun JsonTransactionStatus(transaction: ApiResponseTransaction) {
    val stateColor =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> MaterialTheme.colorScheme.primary
            ApiResponseTransactionState.DUPLICATE -> MaterialTheme.colorScheme.tertiary
            ApiResponseTransactionState.ERROR -> MaterialTheme.colorScheme.error
        }
    val label =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> "Imported"
            ApiResponseTransactionState.DUPLICATE -> "Duplicate"
            ApiResponseTransactionState.ERROR -> "Error"
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = stateColor,
        maxLines = 1,
        modifier =
            Modifier
                .background(stateColor.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun jsonObjectChildPath(
    parentPath: String,
    key: String,
): String =
    if (parentPath == "$") {
        "$.$key"
    } else {
        "$parentPath.$key"
    }

private data class ApiTrafficPair(
    val request: ApiRequest?,
    val response: ApiResponse?,
)

private fun pairRequestsAndResponses(
    requests: List<ApiRequest>,
    responses: List<ApiResponse>,
): Map<ApiRequestId, ApiTrafficPair> {
    val responsesByRequestId = responses.associateBy { it.requestId }
    val requestPairs =
        requests.map { request ->
            request.id to
                ApiTrafficPair(
                    request = request,
                    response = responsesByRequestId[request.id],
                )
        }
    val orphanPairs =
        responses
            .filter { response -> requests.none { request -> request.id == response.requestId } }
            .map { response -> response.requestId to ApiTrafficPair(request = null, response = response) }

    return (requestPairs + orphanPairs)
        .sortedByDescending { (_, pair) -> pair.request?.requestedAt ?: pair.response?.respondedAt ?: Instant.DISTANT_PAST }
        .toMap()
}

private fun ApiRequest.displayBody(): String =
    buildString {
        appendLine("$method $url")
        if (headers.isNotEmpty()) {
            appendLine()
            appendLine("Headers:")
            headers.forEach { header ->
                appendLine("${header.key}: ${header.value}")
            }
        }
    }.trimEnd()

private fun MonzoAccountsDownloadResult.displaySummary(): String =
    if (skipped) {
        "Accounts already downloaded: $accountCount account(s) (skipped)."
    } else {
        "Accounts downloaded: $accountCount account(s)."
    }

private fun MonzoDownloadResult.displaySummary(): String =
    "Transactions downloaded: $accountCount account(s), $transactionResponseCount new response page(s)."

private fun MonzoDownloadProgress.downloadDetail(): String =
    "Downloading account $accountIndex/$accountCount, page $page. $downloadedResponsePageCount response page(s) downloaded so far."

private fun MonzoImportResult.displaySummary(): String =
    buildString {
        append("Import complete: ")
        append(accountCount)
        append(" account(s), ")
        append(transactionCount)
        append(" imported transaction(s)")
        if (personCount > 0) {
            append(", ")
            append(personCount)
            append(" person(s) created")
        }
        if (duplicateCount > 0) {
            append(", ")
            append(duplicateCount)
            append(" duplicate(s)")
        }
        if (errorCount > 0) {
            append(", ")
            append(errorCount)
            append(" error(s)")
        }
        append(".")
    }

private fun monzoAccountsDownloadTaskKey(credentialId: MonzoCredentialId): String = "monzo-accounts-download-cred-${credentialId.id}"

private fun monzoTransactionsDownloadTaskKey(credentialId: MonzoCredentialId): String =
    "monzo-transactions-download-cred-${credentialId.id}"

private fun monzoImportTaskKey(sessionId: ApiSessionId): String = "monzo-import-${sessionId.id}"

private fun JsonElement.childCount(): Int =
    when (this) {
        is JsonObject -> size
        is JsonArray -> size
        else -> 0
    }

private fun JsonElement.summary(expanded: Boolean): String =
    when (this) {
        is JsonObject -> if (expanded) "{" else "{...} $size item(s)"
        is JsonArray -> if (expanded) "[" else "[...] $size item(s)"
        JsonNull -> "null"
        is JsonPrimitive -> toString()
    }
