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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.monzo.MonzoImportProgress
import com.moneymanager.ui.monzo.MonzoImportResult
import com.moneymanager.ui.monzo.createMonzoApiClient
import com.moneymanager.ui.monzo.importMonzoData
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
    deviceId: DeviceId,
    onMonzoConnectClick: () -> Unit = {},
    onSessionClick: (ApiSession) -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var sessions by remember { mutableStateOf<List<ApiSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sessionToRevoke by remember { mutableStateOf<ApiSession?>(null) }

    // Per-session import state keyed by session id
    var importingSessionId by remember { mutableStateOf<ApiSessionId?>(null) }
    var importResultBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoImportResult>>(emptyMap()) }
    var importProgressBySession by remember { mutableStateOf<Map<ApiSessionId, MonzoImportProgress?>>(emptyMap()) }
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
                            val isImporting = importingSessionId == session.id
                            val importResult = importResultBySession[session.id]
                            val importProgress = importProgressBySession[session.id]
                            val importError = importErrorBySession[session.id]

                            ApiSessionCard(
                                session = session,
                                isActive = isActive,
                                isImporting = isImporting,
                                importResult = importResult,
                                importProgress = importProgress,
                                importError = importError,
                                onRevoke = { sessionToRevoke = session },
                                onOpenTraffic = { onSessionClick(session) },
                                onImport = {
                                    importingSessionId = session.id
                                    importResultBySession = importResultBySession - session.id
                                    importProgressBySession = importProgressBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    scope.launch {
                                        try {
                                            val result =
                                                importMonzoData(
                                                    token = session.token,
                                                    apiClient =
                                                        createMonzoApiClient(
                                                            sessionId = session.id,
                                                            apiSessionRepository = apiSessionRepository,
                                                        ),
                                                    onProgress = { progress ->
                                                        importProgressBySession = importProgressBySession + (session.id to progress)
                                                    },
                                                )
                                            importResultBySession = importResultBySession + (session.id to result)
                                        } catch (expected: Exception) {
                                            val message = "Import failed: ${expected.message}"
                                            logger.error(expected) { message }
                                            importErrorBySession = importErrorBySession + (session.id to message)
                                        } finally {
                                            importingSessionId = null
                                            importProgressBySession = importProgressBySession - session.id
                                        }
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
    isImporting: Boolean,
    importResult: MonzoImportResult?,
    importProgress: MonzoImportProgress?,
    importError: String?,
    onRevoke: () -> Unit,
    onOpenTraffic: () -> Unit,
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
                    "${session.token.take(8)}…${session.token.takeLast(8)}"
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

                importResult?.let { result ->
                    Text(
                        text =
                            "Import complete: ${result.accountCount} account(s), " +
                                "${result.transactionCount} transaction(s).",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (isImporting) {
                    Text(
                        text =
                            if (importProgress == null) {
                                "Preparing import…"
                            } else {
                                "Importing account ${importProgress.accountIndex}/${importProgress.accountCount}, " +
                                    "page ${importProgress.page}. " +
                                    "${importProgress.importedTransactionCount} transaction(s) so far."
                            },
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
                        onClick = onImport,
                        enabled = !isImporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Import")
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
    onBack: () -> Unit,
) {
    var session by remember { mutableStateOf<ApiSession?>(null) }
    var requests by remember { mutableStateOf<List<ApiRequest>>(emptyList()) }
    var responses by remember { mutableStateOf<List<ApiResponse>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val pairs = remember(requests, responses) { pairRequestsAndResponses(requests, responses) }

    LaunchedEffect(sessionId) {
        try {
            session = apiSessionRepository.getSessionById(sessionId)
            requests = apiSessionRepository.getRequestsBySession(sessionId)
            responses = apiSessionRepository.getResponsesBySession(sessionId)
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
                            ApiTrafficPairCard(pair = pair)
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
private fun ApiTrafficPairCard(pair: ApiTrafficPair) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
        JsonViewer(json = json)
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
private fun JsonViewer(json: String) {
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
        Column {
            JsonTreeNode(
                label = null,
                element = jsonElement,
                depth = 0,
            )
        }
    }
}

@Composable
private fun JsonTreeNode(
    label: String?,
    element: JsonElement,
    depth: Int,
) {
    val childCount = element.childCount()
    val expandable = childCount > 0
    var expanded by remember(label, element) { mutableStateOf(depth == 0) }
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

    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = (depth * 12).dp)
                .then(
                    if (expandable) {
                        Modifier.clickable { expanded = !expanded }
                    } else {
                        Modifier
                    },
                ),
    )

    if (expanded) {
        when (element) {
            is JsonObject -> {
                element.entries.forEach { (key, value) ->
                    JsonTreeNode(
                        label = "\"$key\"",
                        element = value,
                        depth = depth + 1,
                    )
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, value ->
                    JsonTreeNode(
                        label = "[$index]",
                        element = value,
                        depth = depth + 1,
                    )
                }
            }
            else -> Unit
        }
    }
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
