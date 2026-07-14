@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.createApiCredential
import com.moneymanager.importengineapi.updateApiCredentialKeys
import com.moneymanager.importengineapi.updateApiCredentialSecrets
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.api.sca.generateScaKeyPair
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The API connections checklist: one row per installed API strategy, showing whether it is connected.
 *
 * Connecting saves a credential and immediately opens the next API still needing one, so a user with several
 * providers walks the list once instead of re-picking a provider from a dropdown each time. Every API can
 * also be skipped for now — the row stays connectable, nothing is hidden.
 *
 * A strategy holds **at most one** credential (enforced by `UNIQUE(strategy_id)` on `api_credential`), so a
 * connected API offers *edit*, never *add another*.
 */
@Composable
fun ApiConnectionsScreen(
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    apiSessionRepository: ApiSessionReadRepository,
    showHeader: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val strategies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        apiImportStrategyRepository.getAllStrategies()
    }
    val credentials by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        apiSessionRepository.getCredentialsFlow()
    }
    val credentialByStrategy = credentials.mapNotNull { c -> c.strategyId?.let { it to c } }.toMap()

    // Skipping is a "not now", not a decision worth persisting: the row stays listed as not connected, so the
    // checklist itself is the reminder. Forgotten when the screen goes away.
    var skipped by remember { mutableStateOf(emptySet<ApiImportStrategyId>()) }
    var expandedStrategyId by remember { mutableStateOf<ApiImportStrategyId?>(null) }

    val sorted = strategies.sortedBy { it.name.lowercase() }
    val connectedCount = sorted.count { credentialByStrategy.containsKey(it.id) }

    fun advanceFrom(strategyId: ApiImportStrategyId) {
        expandedStrategyId =
            nextApiToSetUp(
                strategies = sorted,
                connectedStrategyIds = credentialByStrategy.keys + strategyId,
                skippedStrategyIds = skipped,
                after = strategyId,
            )?.id
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "API connections",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                onBack?.let { TextButton(onClick = it) { Text("Back") } }
            }
        }

        if (sorted.isEmpty()) {
            Text(
                "No API strategies installed yet. Install one from the strategy catalog first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            "$connectedCount of ${sorted.size} connected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { connectedCount.toFloat() / sorted.size },
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.id.toString() }) { strategy ->
                ApiConnectionRow(
                    strategy = strategy,
                    credential = credentialByStrategy[strategy.id],
                    expanded = expandedStrategyId == strategy.id,
                    onExpandedChange = { expand ->
                        expandedStrategyId = strategy.id.takeIf { expand }
                        // Re-opening a skipped API means the user changed their mind about it.
                        if (expand) skipped = skipped - strategy.id
                    },
                    onSaved = { advanceFrom(strategy.id) },
                    onSkip = {
                        skipped = skipped + strategy.id
                        advanceFrom(strategy.id)
                    },
                )
            }
        }
    }
}

/**
 * The next API worth setting up after [after]: the first strategy in list order past [after] that is neither
 * connected nor skipped, wrapping around to earlier ones. Null when nothing is left to set up.
 */
internal fun nextApiToSetUp(
    strategies: List<ApiImportStrategy>,
    connectedStrategyIds: Set<ApiImportStrategyId>,
    skippedStrategyIds: Set<ApiImportStrategyId>,
    after: ApiImportStrategyId?,
): ApiImportStrategy? {
    val candidates = strategies.filter { it.id !in connectedStrategyIds && it.id !in skippedStrategyIds }
    if (candidates.isEmpty()) return null
    val afterIndex = strategies.indexOfFirst { it.id == after }
    if (afterIndex < 0) return candidates.first()
    return candidates.firstOrNull { strategies.indexOfFirst { s -> s.id == it.id } > afterIndex }
        ?: candidates.first()
}

@Composable
private fun ApiConnectionRow(
    strategy: ApiImportStrategy,
    credential: MonzoCredential?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onSkip: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strategy.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text =
                            when {
                                credential != null -> "Connected · ${maskToken(credential.token)}"
                                strategy.isSigned() -> "Needs an API key and secret"
                                else -> "Needs an access token"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (credential != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                if (expanded) {
                    TextButton(onClick = { onExpandedChange(false) }) { Text("Close") }
                } else if (credential != null) {
                    OutlinedButton(onClick = { onExpandedChange(true) }) { Text("Update credentials") }
                } else {
                    Button(onClick = { onExpandedChange(true) }) { Text("Connect") }
                }
            }

            if (expanded) {
                HorizontalDivider()
                ApiCredentialForm(
                    strategy = strategy,
                    credential = credential,
                    onOpenTokenPage = { url -> uriHandler.openUri(url) },
                    onSubmit = { token, secret, onFailure ->
                        scope.launch {
                            try {
                                if (credential == null) {
                                    importEngine.createApiCredential(
                                        token = token,
                                        createdAt = Clock.System.now(),
                                        type = defaultSessionTypeFor(strategy),
                                        strategyId = strategy.id,
                                        apiSecret = secret,
                                    )
                                } else {
                                    importEngine.updateApiCredentialSecrets(
                                        credentialId = credential.id,
                                        token = token,
                                        apiSecret = secret,
                                    )
                                }
                                onSaved()
                            } catch (expected: Exception) {
                                onFailure(saveFailureMessage(expected))
                            }
                        }
                    },
                    onSkip = onSkip.takeIf { credential == null },
                )

                if (credential != null && strategy.signing != null) {
                    HorizontalDivider()
                    SigningKeySection(
                        publicKey = credential.publicKey,
                        onGenerateSigningKey = {
                            scope.launch {
                                val keyPair = withContext(Dispatchers.Default) { generateScaKeyPair() }
                                importEngine.updateApiCredentialKeys(
                                    credentialId = credential.id,
                                    privateKey = keyPair.privateKeyPem,
                                    publicKey = keyPair.publicKeyPem,
                                )
                            }
                        },
                        onCopyText = { text -> scope.launch { clipboard.setPlainText(text) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiCredentialForm(
    strategy: ApiImportStrategy,
    credential: MonzoCredential?,
    onOpenTokenPage: (String) -> Unit,
    onSubmit: (token: String, secret: String?, onFailure: (String) -> Unit) -> Unit,
    onSkip: (() -> Unit)?,
) {
    val isSigned = strategy.isSigned()
    var tokenInput by remember(strategy.id) { mutableStateOf("") }
    var secretInput by remember(strategy.id) { mutableStateOf("") }
    var isSaving by remember(strategy.id) { mutableStateOf(false) }
    var errorMessage by remember(strategy.id) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val instructions = connectInstructions(strategy)
        if (instructions.isNotEmpty()) {
            Text("How to connect ${strategy.name}", style = MaterialTheme.typography.labelLarge)
            instructions.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
        }
        providerTokenPageUrl(strategy)?.let { url ->
            OutlinedButton(onClick = { onOpenTokenPage(url) }) { Text("Open ${strategy.name} token page") }
        }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = {
                tokenInput = it
                errorMessage = null
            },
            label = { Text(if (isSigned) "API key" else "Access token") },
            placeholder = {
                Text(if (credential == null) "Paste it here" else "Paste the replacement here")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = errorMessage != null,
        )

        if (isSigned) {
            OutlinedTextField(
                value = secretInput,
                onValueChange = {
                    secretInput = it
                    errorMessage = null
                },
                label = { Text("API secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = errorMessage != null,
            )
        }

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    isSaving = true
                    errorMessage = null
                    onSubmit(tokenInput.trim(), secretInput.trim().takeIf { isSigned }) { message ->
                        isSaving = false
                        errorMessage = message
                    }
                },
                enabled = !isSaving && tokenInput.isNotBlank() && (!isSigned || secretInput.isNotBlank()),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (credential == null) "Save and continue" else "Replace credentials")
                }
            }
            onSkip?.let {
                TextButton(onClick = it, enabled = !isSaving) { Text("Skip for now") }
            }
        }
    }
}

private fun maskToken(token: String): String {
    if (token.length <= 16) return "••••"
    return "${token.take(8)}…${token.takeLast(8)}"
}

/** The token column is globally unique, so a clash means the token belongs to another connection. */
private fun saveFailureMessage(error: Throwable): String {
    val message = error.message.orEmpty().uppercase()
    if ("UNIQUE" in message && "TOKEN" in message) {
        return "That token is already used by another connection."
    }
    return "Couldn't save the credentials: ${error.message ?: error::class.simpleName}"
}
