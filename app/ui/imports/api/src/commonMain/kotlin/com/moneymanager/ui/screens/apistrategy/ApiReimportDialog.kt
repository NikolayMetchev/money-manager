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
import co.touchlab.kermit.Logger
import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.ApiRerunCounts
import com.moneymanager.apiimporter.executeApiReimport
import com.moneymanager.apiimporter.planApiReimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.qifimporter.Reimports
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.components.imports.SourceRerunPreview
import com.moneymanager.ui.components.imports.rememberAccountNamer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val logger = Logger.withTag("ApiReimportDialog")

/**
 * Re-imports an already-imported API session so a strategy config change (e.g. an internal-transfer
 * reconcile bridge added after the fact) applies retroactively: deletes what the session created, then
 * re-parses its stored responses under the current strategy, re-running the other files' records
 * matched against it and putting hand edits back, as CSV and QIF re-import do.
 */
@Suppress("LongParameterList")
@Composable
fun ApiReimportDialog(
    session: ApiSession,
    strategy: ApiImportStrategy,
    apiReads: ApiImportReads,
    reimports: Reimports,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (SourceRerunResult<ApiRerunCounts>) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val accountName = rememberAccountNamer(apiReads.accountRepository)
    var plan by remember { mutableStateOf<SourceRerunPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var executeProgress by remember { mutableStateOf<Progress?>(null) }

    LaunchedEffect(session.id) {
        plan = planApiReimport(session.id, apiReads.apiSessionRepository, reimports.reads)
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
                    null -> {
                        CircularProgressIndicator()
                    }

                    else -> {
                        Text(
                            text = "Re-imports this session under the current strategy configuration (\"${strategy.name}\").",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SourceRerunPreview(currentPlan, recordNoun = "item", accountName = accountName)
                        if (isRunning) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ApiReimportProgress(executeProgress ?: Progress("Starting re-import"))
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
                                    apiReads = apiReads,
                                    reads = reimports.reads,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    rerunAffected = reimports,
                                    onProgress = { executeProgress = it },
                                )
                            onComplete(result)
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.e(expected) { "Re-import failed: ${expected.message}" }
                            errorMessage = "Re-import failed: ${expected.message}"
                            isRunning = false
                        } finally {
                            executeProgress = null
                        }
                    }
                },
                enabled = !isRunning && currentPlan != null,
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
