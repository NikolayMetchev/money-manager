@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
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
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

@Composable
fun ApiSessionsScreen(
    apiSessionRepository: ApiSessionRepository,
    deviceId: DeviceId,
    onMonzoConnectClick: () -> Unit = {},
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
            sessions = apiSessionRepository.getSessionsByDevice(deviceId)
            isLoading = false
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions) { session ->
                        val isActive = session.revokedAt == null &&
                            (session.expiresAt == null || session.expiresAt > Clock.System.now())
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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.type.name.replaceFirstChar { it.uppercase() },
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
