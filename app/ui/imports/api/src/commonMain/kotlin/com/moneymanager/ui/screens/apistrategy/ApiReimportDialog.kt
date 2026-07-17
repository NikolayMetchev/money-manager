@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimporter.ApiReimportPlan
import com.moneymanager.apiimporter.ApiReimportResult
import com.moneymanager.apiimporter.executeApiReimport
import com.moneymanager.apiimporter.planApiReimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Re-imports an already-imported API session so a strategy config change (e.g. an internal-transfer
 * reconcile bridge added after the fact) applies retroactively: deletes the transfers/trades this
 * session itself created, then re-runs the import under the current strategy — which re-parses the
 * session's stored responses from scratch under the new config.
 */
@Suppress("LongParameterList")
@Composable
fun ApiReimportDialog(
    session: ApiSession,
    strategy: ApiImportStrategy,
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (ApiReimportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var plan by remember { mutableStateOf<ApiReimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var executeProgress by remember { mutableStateOf<ImportProgress?>(null) }

    LaunchedEffect(session.id) {
        plan = planApiReimport(session.id, apiSessionRepository)
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Re-import session") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                when (val currentPlan = plan) {
                    null -> CircularProgressIndicator()
                    else -> {
                        Text(
                            text =
                                "This will delete ${currentPlan.transferIds.size} transaction(s) and " +
                                    "${currentPlan.tradeIds.size} trade(s) created by this session, then re-import " +
                                    "them under the current strategy configuration (\"${strategy.name}\").",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (currentPlan.isEmpty) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "This session created no transactions or trades — nothing to re-import.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isRunning) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ApiReimportProgress(executeProgress ?: ImportProgress("Starting re-import"))
                        }
                    }
                }
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val currentPlan = plan
            LoadingTextButton(
                onClick = {
                    if (currentPlan == null) return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val result =
                                executeApiReimport(
                                    plan = currentPlan,
                                    session = session,
                                    strategy = strategy,
                                    apiSessionRepository = apiSessionRepository,
                                    accountRepository = accountRepository,
                                    currencyRepository = currencyRepository,
                                    cryptoRepository = cryptoRepository,
                                    accountAttributeRepository = accountAttributeRepository,
                                    transactionRepository = transactionRepository,
                                    transferRelationshipRepository = transferRelationshipRepository,
                                    tradeRepository = tradeRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    onProgress = { executeProgress = it },
                                )
                            onComplete(result)
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.error(expected) { "Re-import failed: ${expected.message}" }
                            errorMessage = "Re-import failed: ${expected.message}"
                            isRunning = false
                        } finally {
                            executeProgress = null
                        }
                    }
                },
                enabled = !isRunning && currentPlan != null && !currentPlan.isEmpty,
                loading = isRunning,
                label = "Re-import",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRunning) { Text("Cancel") }
        },
    )
}

@Composable
private fun ApiReimportProgress(progress: ImportProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${progress.detail}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
