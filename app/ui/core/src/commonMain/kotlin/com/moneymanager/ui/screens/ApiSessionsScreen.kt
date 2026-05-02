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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
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
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.ui.background.LocalBackgroundTaskManager
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.monzo.MonzoDownloadProgress
import com.moneymanager.ui.monzo.MonzoDownloadResult
import com.moneymanager.ui.monzo.MonzoImportResult
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
    deviceId: DeviceId,
    onMonzoConnectClick: () -> Unit = {},
    onSessionClick: (ApiSession) -> Unit = {},
    onTransactionsImported: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val backgroundTasks = LocalBackgroundTaskManager.current
    val clipboardManager = LocalClipboardManager.current

    var sessions by remember { mutableStateOf<List<ApiSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sessionToRevoke by remember { mutableStateOf<ApiSession?>(null) }

    // Per-session download/import state keyed by session id
    var downloadResultBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoDownloadResult>>(emptyMap()) }
    var downloadProgressBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoDownloadProgress?>>(emptyMap()) }
    var importResultBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoImportResult>>(emptyMap()) }
    var importErrorBySession by remember { mutableStateOf<Map<ApiSessionId, String>>(emptyMap()) }

    fun refreshSessions() {
        scope.launch {
            try {
                sessions = apiSessionRepository.getSessionsByDevice(deviceId)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshSessions()
    }

    sessionToRevoke?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToRevoke = null },
            title = { Text("Revoke Session?") },
            text = {
                Text(
                    "This will revoke the session created on ${session.createdAt.displayDateTime()}. " +
                        "You will need to reconnect to use API features again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToRevoke = null
                        scope.launch {
                            try {
                                apiSessionRepository.revokeSession(session.id, Clock.System.now())
                                refreshSessions()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to revoke session: ${expected.message}" }
                            }
                        }
                    },
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRevoke = null }) { Text("Cancel") }
            },
        )
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
            Text(
                text = "API Sessions",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = onMonzoConnectClick) {
                Text("+ Connect")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            sessions.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No API sessions yet. Click '+ Connect' to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sessions) { session ->
                            val expiresAt = session.expiresAt
                            val isActive =
                                session.revokedAt == null &&
                                    (expiresAt == null || expiresAt > Clock.System.now())
                            val isDownloading = backgroundTasks.isRunning(monzoDownloadTaskKey(session.id))
                            val isImporting = backgroundTasks.isRunning(monzoImportTaskKey(session.id))
                            val downloadResult = downloadResultBySession[session.id]
                            val downloadProgress = downloadProgressBySession[session.id]
                            val importResult = importResultBySession[session.id]
                            val importError = importErrorBySession[session.id]

                            ApiSessionCard(
                                session = session,
                                isActive = isActive,
                                isDownloading = isDownloading,
                                isImporting = isImporting,
                                downloadResult = downloadResult,
                                downloadProgress = downloadProgress,
                                importResult = importResult,
                                importError = importError,
                                onRevoke = { sessionToRevoke = session },
                                onOpenTraffic = { onSessionClick(session) },
                                onDownload = {
                                    downloadResultBySession = downloadResultBySession - session.id
                                    downloadProgressBySession = downloadProgressBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    backgroundTasks.startTask(
                                        key = monzoDownloadTaskKey(session.id),
                                        title = "Download Transactions",
                                        initialDetail = "Starting Monzo download for session #${session.id}.",
                                    ) {
                                        val result =
                                            downloadMonzoTransactions(
                                                token = session.token,
                                                apiClient =
                                                    createApiClient(
                                                        trafficRecorder =
                                                            ApiSessionTrafficRecorder(
                                                                sessionId = session.id,
                                                                apiSessionRepository = apiSessionRepository,
                                                            ),
                                                    ),
                                                onProgress = { progress ->
                                                    downloadProgressBySession = downloadProgressBySession + (session.id to progress)
                                                    update(progress.downloadDetail())
                                                },
                                            )
                                        downloadResultBySession = downloadResultBySession + (session.id to result)
                                        downloadProgressBySession = downloadProgressBySession - session.id
                                        result.displaySummary()
                                    }
                                },
                                onImport = {
                                    importResultBySession = importResultBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    backgroundTasks.startTask(
                                        key = monzoImportTaskKey(session.id),
                                        title = "Import Transactions",
                                        initialDetail = "Starting transaction import for session #${session.id}.",
                                    ) {
                                        val result =
                                            importMonzoSessionTransactions(
                                                apiSessionRepository = apiSessionRepository,
                                                accountRepository = accountRepository,
                                                currencyRepository = currencyRepository,
                                                transactionRepository = transactionRepository,
                                                transferSourceQueries = transferSourceQueries,
                                                entitySourceQueries = entitySourceQueries,
                                                deviceId = deviceId,
                                                sessionId = session.id,
                                                onProgress = ::update,
                                            )
                                        importResultBySession = importResultBySession + (session.id to result)
                                        onTransactionsImported()
                                        result.displaySummary()
                                    }
                                },
                                onCopyError = { error ->
                                    clipboardManager.setText(AnnotatedString(error))
                                },
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
private fun ApiSessionCard(
    session: ApiSession,
    isActive: Boolean,
    isDownloading: Boolean,
    isImporting: Boolean,
    downloadResult: MonzoDownloadResult?,
    downloadProgress: MonzoDownloadProgress?,
    importResult: MonzoImportResult?,
    importError: String?,
    onRevoke: () -> Unit,
    onOpenTraffic: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onCopyError: (String) -> Unit,
) {
    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenTraffic),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        session.type.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                )
                SessionStatusBadge(isActive = isActive)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Created: ${session.createdAt.displayDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            session.expiresAt?.let { expiresAt ->
                Text(
                    text = "Expires: ${expiresAt.displayDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            session.revokedAt?.let { revokedAt ->
                Text(
                    text = "Revoked: ${revokedAt.displayDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val displayToken =
                if (session.token.length > 16) {
                    "${session.token.take(8)}...${session.token.takeLast(8)}"
                } else {
                    session.token
                }
            Text(
                text = displayToken,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isActive) {
                Spacer(modifier = Modifier.height(8.dp))

                downloadResult?.let { result ->
                    Text(
                        text = result.displaySummary(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                importResult?.let { result ->
                    Text(
                        text = result.displaySummary(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (isDownloading) {
                    Text(
                        text =
                            if (downloadProgress == null) {
                                "Preparing download..."
                            } else {
                                "Downloading account ${downloadProgress.accountIndex}/${downloadProgress.accountCount}, " +
                                    "page ${downloadProgress.page}. " +
                                    "${downloadProgress.downloadedResponsePageCount} response(s) downloaded so far."
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (isImporting) {
                    Text(
                        text = "Importing transactions...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                importError?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onCopyError(error) }) {
                            Text("Copy")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading && !isImporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Download Transactions")
                        }
                    }

                    Button(
                        onClick = onImport,
                        enabled = !isDownloading && !isImporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Import Transactions")
                        }
                    }

                    OutlinedButton(onClick = onOpenTraffic) {
                        Text("Traffic")
                    }

                    OutlinedButton(onClick = onRevoke) {
                        Text("Revoke")
                    }
                }
            }
        }
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

                // Find and scroll to the pair that contains the highlighted response
                val highlightedPairIndex =
                    remember(highlightRequestId, highlightJsonPath, pairs, responseTransactionsByResponseId) {
                        if (highlightRequestId == null && highlightJsonPath == null) {
                            -1
                        } else {
                            // +1 accounts for the summary item at index 0
                            // First try to match by jsonPath in response transactions (for transaction sources)
                            val byJsonPath =
                                if (highlightJsonPath != null) {
                                    pairs.indexOfFirst { pair ->
                                        pair.response != null &&
                                            (highlightRequestId == null || pair.request?.id == highlightRequestId) &&
                                            responseTransactionsByResponseId[pair.response.id]
                                                ?.any { it.jsonPath.value == highlightJsonPath } == true
                                    }
                                } else {
                                    -1
                                }
                            // Fall back to matching by requestId alone (for entity/account sources)
                            val result =
                                if (byJsonPath >= 0) {
                                    byJsonPath
                                } else if (highlightRequestId != null) {
                                    pairs.indexOfFirst { pair -> pair.request?.id == highlightRequestId }
                                } else {
                                    -1
                                }
                            if (result >= 0) result + 1 else -1
                        }
                    }

                LaunchedEffect(highlightedPairIndex) {
                    if (highlightedPairIndex >= 0) {
                        lazyListState.animateScrollToItem(highlightedPairIndex)
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
                        items(pairs) { pair ->
                            val responseTransactions =
                                pair.response?.let { responseTransactionsByResponseId[it.id] }
                                    ?: emptyList()
                            val requestMatches = highlightRequestId == null || pair.request?.id == highlightRequestId
                            val isHighlighted =
                                (highlightRequestId != null || highlightJsonPath != null) &&
                                    requestMatches &&
                                    (
                                        // Highlight by specific jsonPath when available (transaction sources)
                                        (highlightJsonPath != null && responseTransactions.any { it.jsonPath.value == highlightJsonPath }) ||
                                            // Highlight by requestId alone when no jsonPath match (entity/account sources)
                                            (highlightJsonPath == null && highlightRequestId != null)
                                    )
                            ApiTrafficPairCard(
                                pair = pair,
                                responseTransactions = responseTransactions,
                                highlightJsonPath = if (isHighlighted) highlightJsonPath else null,
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
            )
        }
    }
}

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
): List<ApiTrafficPair> {
    val responsesByRequestId = responses.associateBy { it.requestId }
    val requestPairs =
        requests.map { request ->
            ApiTrafficPair(
                request = request,
                response = responsesByRequestId[request.id],
            )
        }
    val orphanResponses =
        responses
            .filter { response -> requests.none { request -> request.id == response.requestId } }
            .map { response -> ApiTrafficPair(request = null, response = response) }

    return (requestPairs + orphanResponses)
        .sortedByDescending { pair -> pair.request?.requestedAt ?: pair.response?.respondedAt ?: Instant.DISTANT_PAST }
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

private fun MonzoDownloadResult.displaySummary(): String =
    "Download complete: $accountCount account(s), $transactionResponseCount transaction response page(s)."

private fun MonzoDownloadProgress.downloadDetail(): String =
    "Downloading account $accountIndex/$accountCount, page $page. $downloadedResponsePageCount response page(s) downloaded so far."

private fun MonzoImportResult.displaySummary(): String =
    buildString {
        append("Import complete: ")
        append(accountCount)
        append(" account(s), ")
        append(transactionCount)
        append(" imported transaction(s)")
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

private fun monzoDownloadTaskKey(sessionId: ApiSessionId): String = "monzo-download-${sessionId.id}"

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
