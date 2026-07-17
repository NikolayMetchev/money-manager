@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimporter.ApiBulkReimportResult
import com.moneymanager.apiimporter.bulkReimportApiSessions
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredentialId
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
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported session of a credential in one go, oldest first, so a strategy
 * config change applies retroactively across the whole history. Single confirm + aggregated summary —
 * no per-session preview. Mirrors [ApiReimportDialog] by design (see [bulkReimportApiSessions]).
 */
@Composable
@Suppress("LongParameterList")
fun ApiReimportAllDialog(
    credentialId: ApiCredentialId,
    importedSessionCount: Int,
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
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var result by remember { mutableStateOf<ApiBulkReimportResult?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text(if (result != null) "Re-import complete" else "Re-import all sessions") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                val currentResult = result
                if (currentResult != null) {
                    Text(
                        text = "Re-imported ${currentResult.sessionsReimported} session(s) under \"${strategy.name}\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text =
                            "Applies the current strategy configuration to all $importedSessionCount " +
                                "already-imported session${if (importedSessionCount == 1) "" else "s"} retroactively, " +
                                "oldest first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    progress?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = it.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        isRunning = true
                        scope.launch {
                            try {
                                result =
                                    bulkReimportApiSessions(
                                        credentialId = credentialId,
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
                                        onProgress = { progress = it },
                                    )
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning,
                    loading = isRunning,
                    label = "Re-import $importedSessionCount session${if (importedSessionCount == 1) "" else "s"}",
                )
            }
        },
        dismissButton = {
            if (result == null) {
                TextButton(onClick = onDismiss, enabled = !isRunning) { Text("Cancel") }
            }
        },
    )
}
