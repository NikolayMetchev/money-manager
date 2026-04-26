@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionType
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

private const val MONZO_DEVELOPER_PORTAL_URL = "https://developers.monzo.com/"

private val logger = logging()

@Composable
@Suppress("DEPRECATION")
fun MonzoAuthScreen(
    apiSessionRepository: ApiSessionRepository,
    deviceId: DeviceId,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    var activeSessions by remember { mutableStateOf<List<ApiSession>>(emptyList()) }
    var tokenInput by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var sessionToRevoke by remember { mutableStateOf<ApiSession?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<MonzoImportResult?>(null) }
    var importProgress by remember { mutableStateOf<MonzoImportProgress?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        activeSessions = apiSessionRepository.getActiveSessions(Clock.System.now())
    }

    fun refreshSessions() {
        scope.launch {
            activeSessions = apiSessionRepository.getActiveSessions(Clock.System.now())
        }
    }

    sessionToRevoke?.let { session ->
        RevokeSessionDialog(
            session = session,
            onConfirm = {
                sessionToRevoke = null
                scope.launch {
                    try {
                        apiSessionRepository.revokeSession(session.id, Clock.System.now())
                        refreshSessions()
                    } catch (expected: Exception) {
                        errorMessage = "Failed to revoke session: ${expected.message}. Please try again."
                    }
                }
            },
            onDismiss = { sessionToRevoke = null },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Monzo Connection",
            style = MaterialTheme.typography.headlineMedium,
        )

        // Instructions card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "How to connect your Monzo account",
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = "1. Open the Monzo Developer Playground in your browser.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "2. Log in with your Monzo account credentials.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "3. Monzo will send a magic link to your email or app. Approve the login.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "4. Copy the access token shown on the playground page.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "5. Paste the token below and tap \"Save Token\".",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Button(
                    onClick = { uriHandler.openUri(MONZO_DEVELOPER_PORTAL_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Monzo Developer Playground")
                }
            }
        }

        // Token input card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Enter Access Token",
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        errorMessage = null
                        successMessage = null
                    },
                    label = { Text("Access Token") },
                    placeholder = { Text("Paste your Monzo access token here") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                    isError = errorMessage != null,
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                successMessage?.let { success ->
                    Text(
                        text = success,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    onClick = {
                        val trimmedToken = tokenInput.trim()
                        if (trimmedToken.isBlank()) {
                            errorMessage =
                                "Token cannot be empty. Copy the access token from the Monzo Developer Playground and paste it here."
                            return@Button
                        }
                        isSaving = true
                        errorMessage = null
                        successMessage = null
                        scope.launch {
                            try {
                                apiSessionRepository.createSession(
                                    token = trimmedToken,
                                    deviceId = deviceId,
                                    createdAt = Clock.System.now(),
                                    expiresAt = null,
                                    type = ApiSessionType.MONZO,
                                )
                                tokenInput = ""
                                successMessage = "Token saved successfully."
                                refreshSessions()
                            } catch (expected: Exception) {
                                errorMessage = "Failed to save token: ${expected.message}. " +
                                    "Ensure the token is valid and try again."
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save Token")
                    }
                }
            }
        }

        // Active sessions card
        if (activeSessions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Active Sessions",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    activeSessions.forEach { session ->
                        ActiveSessionItem(
                            session = session,
                            onRevoke = { sessionToRevoke = session },
                        )
                    }
                }
            }
        }

        // Import data card - only shown when there are active sessions
        if (activeSessions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Import Data",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Text(
                        text = "Fetch all accounts and transactions from Monzo and store them locally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    importResult?.let { result ->
                        Text(
                            text =
                                "Import complete: ${result.accountCount} account(s), " +
                                    "${result.transactionCount} transaction(s).",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (isImporting) {
                        val progress = importProgress
                        Text(
                            text =
                                if (progress == null) {
                                    "Preparing import..."
                                } else {
                                    "Importing account ${progress.accountIndex}/${progress.accountCount}, " +
                                        "page ${progress.page}. " +
                                        "${progress.importedTransactionCount} transaction(s) imported so far."
                                },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                            TextButton(
                                onClick = { clipboardManager.setText(AnnotatedString(error)) },
                            ) {
                                Text("Copy")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val session = activeSessions.firstOrNull() ?: return@Button
                            isImporting = true
                            importResult = null
                            importProgress = null
                            importError = null
                            scope.launch {
                                try {
                                    importResult =
                                        importMonzoData(
                                            token = session.token,
                                            apiClient =
                                                createMonzoApiClient(
                                                    sessionId = session.id,
                                                    apiSessionRepository = apiSessionRepository,
                                                ),
                                            onProgress = { progress ->
                                                importProgress = progress
                                            },
                                        )
                                } catch (expected: Exception) {
                                    val message = "Import failed: ${expected.message}"
                                    logger.error(expected) { message }
                                    importError = message
                                } finally {
                                    isImporting = false
                                    importProgress = null
                                }
                            }
                        },
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Import Data from Monzo")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ActiveSessionItem(
    session: ApiSession,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Created: ${session.createdAt.displayDateTime()}",
                style = MaterialTheme.typography.bodySmall,
            )
            val expiresAt = session.expiresAt
            if (expiresAt != null) {
                Text(
                    text = "Expires: ${expiresAt.displayDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Show only the beginning and end of the token for identification
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
        }

        OutlinedButton(
            onClick = onRevoke,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Revoke")
        }
    }
}

@Composable
private fun RevokeSessionDialog(
    session: ApiSession,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revoke Session?") },
        text = {
            Text(
                "This will revoke the Monzo access token created on ${session.createdAt.displayDateTime()}. " +
                    "You will need to reconnect to use Monzo features again.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Revoke")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
