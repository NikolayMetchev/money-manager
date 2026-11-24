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
internal fun CredentialCard(
    credential: ApiCredential,
    secrets: StoredApiCredential?,
    signingKeyError: String?,
    dateRangeBySession: Map<String, ImportFileDateRange>,
    providerLabel: String?,
    requiresSigning: Boolean,
    transactionsBlockReason: String?,
    onGenerateSigningKey: () -> Unit,
    onCopyText: (String) -> Unit,
    sessions: List<ApiSession>,
    isDownloading: Boolean,
    downloadResult: ApiSessionDownloadResult?,
    authFailure: ApiDownloadOutcome.AuthFailed?,
    strategy: ApiImportStrategy?,
    otherTokens: Set<String>,
    onTokenSaved: () -> Unit,
    downloadProgress: Progress?,
    importResultBySession: Map<ApiSessionId, ApiSessionImportResult>,
    importErrorBySession: Map<ApiSessionId, String>,
    importProgressBySession: Map<ApiSessionId, Progress>,
    importedSessionRevisions: Set<ApiSessionImportRevision>,
    selectedStrategyRevision: Long?,
    isImportingSession: (ApiSessionId) -> Boolean,
    hasEverBeenImported: (ApiSessionId) -> Boolean,
    importedSessionCount: Int,
    isAnySessionImporting: Boolean,
    forceFullDownload: Boolean,
    onForceFullDownloadChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onImport: (ApiSession) -> Unit,
    onReimport: (ApiSession) -> Unit,
    onReimportAll: (Int) -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onCopyError: (String) -> Unit,
) {
    val token = secrets?.token
    val displayToken =
        when {
            token == null -> "Credentials locked"
            token.length > 16 -> "${token.take(8)}...${token.takeLast(8)}"
            else -> token
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    "Credential · " + (providerLabel ?: "API"),
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

            if (requiresSigning) {
                SigningKeySection(
                    publicKey = secrets?.publicKeyPem,
                    errorMessage = signingKeyError,
                    onGenerateSigningKey = onGenerateSigningKey,
                    onCopyText = onCopyText,
                )
            }

            downloadResult?.let {
                Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (isDownloading) {
                Text(
                    text = downloadProgress?.detail ?: "Downloading...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forceFullDownload, onCheckedChange = onForceFullDownloadChange, enabled = !isDownloading)
                Column {
                    Text("Re-download full history", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Ignores what earlier sessions already downloaded and fetches everything from scratch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(onClick = onDownload, enabled = !isDownloading, modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Download")
                }
            }
            transactionsBlockReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (authFailure != null) {
                Text(
                    text = authFailure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (strategy != null) {
                    ApiConnectionRow(
                        strategy = strategy,
                        credential = secrets,
                        tokensUsedElsewhere = otherTokens,
                        expanded = true,
                        onExpandedChange = {},
                        onSaved = onTokenSaved,
                        onSkip = {},
                    )
                }
            }
            if (importedSessionCount > 0) {
                OutlinedButton(
                    onClick = { onReimportAll(importedSessionCount) },
                    enabled = !isAnySessionImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-import all ($importedSessionCount)")
                }
            }

            if (sessions.isNotEmpty()) {
                HorizontalDivider()
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        dateRange = dateRangeBySession[session.id.toString()],
                        isImporting = isImportingSession(session.id),
                        isAlreadyImported =
                            sessionImportedAtCurrentRevision(
                                sessionId = session.id,
                                currentRevision = selectedStrategyRevision,
                                importedSessionRevisions = importedSessionRevisions,
                            ),
                        hasEverBeenImported = hasEverBeenImported(session.id),
                        importResult = importResultBySession[session.id],
                        importError = importErrorBySession[session.id],
                        importProgress = importProgressBySession[session.id],
                        onImport = { onImport(session) },
                        onReimport = { onReimport(session) },
                        onOpenTraffic = { onSessionClick(session) },
                        onCopyError = onCopyError,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SessionRow(
    session: ApiSession,
    dateRange: ImportFileDateRange?,
    isImporting: Boolean,
    isAlreadyImported: Boolean,
    hasEverBeenImported: Boolean,
    importResult: ApiSessionImportResult?,
    importError: String?,
    importProgress: Progress?,
    onImport: () -> Unit,
    onReimport: () -> Unit,
    onOpenTraffic: () -> Unit,
    onCopyError: (String) -> Unit,
) {
    val isActive = session.expiresAt?.let { it > Clock.System.now() } ?: true

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
            Text(text = "Session", style = MaterialTheme.typography.labelLarge)
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

        dateRange?.let { range ->
            Text(
                text =
                    "Transactions ${range.earliest.displayDate()} → ${range.latest.displayDate()} " +
                        "(${range.transactionCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        session.importDurationMillis?.let { durationMillis ->
            Text(
                text = "Import took: ${formatElapsedTime(durationMillis.milliseconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        importResult?.let {
            Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (isImporting) {
            val fraction = importProgress?.fraction
            // Before the first engine progress arrives, the import is still in the UI-side preparation
            // phase (resolving the strategy, scanning for counterparties, loading the session) — so label
            // it accurately as "Preparing import…" rather than the misleading "Importing…". Once progress
            // flows, each phase reports its own detail ("Counterparties prepared.", "Resolving accounts",
            // "Importing transactions", "Import finalized.", …), which we surface as the label.
            val percent = fraction?.let { " (${(it * 100).toInt().coerceIn(0, 100)}%)" }.orEmpty()
            val label = importProgress?.let { it.detail + percent } ?: "Preparing import…"
            Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            // Indeterminate during preparation (no fraction yet), determinate once a phase reports one.
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onImport,
                    // Once a session has ever been imported, plain Import can't retroactively apply a
                    // changed strategy (its own entities dedupe it to a no-op) — Re-import is the only
                    // path that deletes and re-runs, so it's the only enabled action from here on.
                    enabled = !isImporting && !isAlreadyImported && !hasEverBeenImported,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Import")
                    }
                }
                if (hasEverBeenImported) {
                    OutlinedButton(onClick = onReimport, enabled = !isImporting, modifier = Modifier.weight(1f)) {
                        Text("Re-import")
                    }
                }
                OutlinedButton(onClick = onOpenTraffic) { Text("Traffic") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Re-import is network-free (it replays the session's stored responses), so it stays
                // available even once the session's token has expired — unlike a fresh Import.
                if (hasEverBeenImported) {
                    OutlinedButton(onClick = onReimport, enabled = !isImporting, modifier = Modifier.weight(1f)) {
                        Text("Re-import")
                    }
                }
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
