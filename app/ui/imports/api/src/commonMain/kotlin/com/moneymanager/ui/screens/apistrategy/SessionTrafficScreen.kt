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
import com.moneymanager.apiimport.ApiDownloadOutcome
import com.moneymanager.apiimport.ApiImportListener
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.apiDownloadTaskKey
import com.moneymanager.apiimport.apiImportTaskKey
import com.moneymanager.apiimport.displaySummary
import com.moneymanager.apiimport.filterCredentialsByStrategy
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
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionReadRepository
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
fun ApiSessionTrafficScreen(
    apiSessionRepository: ApiSessionReadRepository,
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
                        session?.let { "API session #${it.id}" } ?: "Session #$sessionId",
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
                var listRootY by remember { mutableFloatStateOf(0f) }
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

                LaunchedEffect(highlightedPairIndex, highlightJsonPath) {
                    if (highlightedPairIndex >= 0) {
                        lazyListState.animateScrollToItem(highlightedPairIndex)
                        if (highlightJsonPath != null) {
                            // Wait for the highlighted node's position to be reported, then
                            // do a second scroll to center it vertically in the viewport.
                            val nodeY =
                                snapshotFlow { highlightNodeRootY }
                                    .first { it >= 0f }
                            val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                            val listStartY = lazyListState.layoutInfo.viewportStartOffset.toFloat()
                            val nodeOffsetInViewport = nodeY - listRootY - listStartY
                            val delta = nodeOffsetInViewport - viewportHeight / 2f
                            lazyListState.scroll { scrollBy(delta) }
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords -> listRootY = coords.positionInRoot().y },
                ) {
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
                            val isHighlighted =
                                shouldHighlightPair(
                                    pair = pair,
                                    responseTransactions = responseTransactions,
                                    highlightRequestId = highlightRequestId,
                                    highlightJsonPath = highlightJsonPath,
                                )
                            ApiTrafficPairCard(
                                pair = pair,
                                responseTransactions = responseTransactions,
                                isHighlighted = isHighlighted,
                                highlightJsonPath = if (isHighlighted) highlightJsonPath else null,
                                onHighlightPositioned =
                                    if (isHighlighted && highlightJsonPath != null) {
                                        { y -> highlightNodeRootY = y }
                                    } else {
                                        null
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
private fun ApiTrafficPairCard(
    pair: ApiTrafficPair,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    isHighlighted: Boolean = false,
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
            onCopy = { scope.launch { clipboard.setPlainText(body) } },
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prettyJson =
        remember(json) {
            runCatching {
                val element = Json.parseToJsonElement(json)
                Json { prettyPrint = true }.encodeToString(element)
            }.getOrDefault(json)
        }
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
            onCopy = { scope.launch { clipboard.setPlainText(prettyJson) } },
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
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
            )
            if (onCopy != null) {
                Icon(
                    imageVector = ContentCopyIcon,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).clickable(onClick = onCopy),
                )
            }
        }
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal data class ApiTrafficPair(
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
