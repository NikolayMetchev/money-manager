package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.FileReimportPlan
import com.moneymanager.csvimporter.ReimportAllPlan
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.csvimporter.source
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val logger = Logger.withTag("ReimportDialogs")

/** The repositories the re-import dialogs' account and currency pickers read. */
data class ReimportPickerRepositories(
    val accountRepository: AccountReadRepository,
    val categoryRepository: CategoryReadRepository,
    val currencyRepository: CurrencyReadRepository,
    val personRepository: PersonReadRepository,
)

/**
 * Re-imports one staged [file] (CSV, QIF, Excel or PDF) so strategy and mapping changes take effect retroactively: shows
 * what it created, the other files' records matched against that and the hand edits it will put back,
 * then (on confirm) deletes it all and imports the file again ([Reimports.reimport]). The account and
 * currency come from what the file created; the person picks them only when it created nothing.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun ReimportDialog(
    file: CsvImport,
    reimports: Reimports,
    pickers: ReimportPickerRepositories,
    onDismiss: () -> Unit,
    onComplete: (SourceRerunResult<CsvImportResult?>) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val accountName = rememberAccountNamer(pickers.accountRepository)

    var plan by remember { mutableStateOf<FileReimportPlan?>(null) }
    var accountId by remember { mutableStateOf<AccountId?>(null) }
    var currencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var executeProgress by remember { mutableStateOf<Progress?>(null) }
    var planProgress by remember { mutableStateOf<Progress?>(null) }

    LaunchedEffect(file.source) {
        try {
            plan = reimports.plan(file, onProgress = { planProgress = it })
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.e(expected) { "Re-import preview failed: ${expected.message}" }
            errorMessage = "Failed to prepare re-import: ${expected.message}"
        }
    }

    val ready = plan?.let { (!it.needsAccount || accountId != null) && (!it.needsCurrency || currencyId != null) } == true

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Re-import ${file.originalFileName}") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                val currentPlan = plan
                when {
                    currentPlan == null -> {
                        if (errorMessage == null) ReimportProgressIndicator(planProgress ?: Progress(PREPARING_REIMPORT))
                    }

                    currentPlan.strategy == null -> {
                        Text(
                            text = "No strategy matches this import. Create or restore a matching strategy first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Text(
                            text = "Strategy: ${currentPlan.strategy?.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ReimportSettingsPickers(
                            needsAccount = currentPlan.needsAccount,
                            needsCurrency = currentPlan.needsCurrency,
                            accountId = accountId,
                            onAccountSelected = { accountId = it },
                            currencyId = currencyId,
                            onCurrencySelected = { currencyId = it },
                            pickers = pickers,
                            enabled = !isRunning,
                        )
                        if (isRunning) {
                            ReimportProgressIndicator(executeProgress ?: Progress("Starting re-import"))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        SourceRerunPreview(currentPlan.rerun, recordNoun = "record", accountName = accountName)
                    }
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = {
                    val currentPlan = plan ?: return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            onComplete(reimports.reimport(currentPlan, accountId, currencyId, onProgress = { executeProgress = it }))
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
                enabled = !isRunning && plan?.strategy != null && ready,
                loading = isRunning,
                label = "Re-import",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRunning,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Re-imports every staged file in [files] in one go, each as [ReimportDialog] would, with one
 * confirm and an aggregated summary. Each file keeps the strategy, account and currency it was
 * imported with; the shared pickers only appear for files that created nothing to read them from.
 */
@Suppress("LongMethod")
@Composable
fun ReimportAllDialog(
    files: List<CsvImport>,
    reimports: Reimports,
    pickers: ReimportPickerRepositories,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val accountName = rememberAccountNamer(pickers.accountRepository)

    var accountId by remember { mutableStateOf<AccountId?>(null) }
    var currencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var result by remember { mutableStateOf<BulkReimportResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var plan by remember { mutableStateOf<ReimportAllPlan?>(null) }
    var planProgress by remember { mutableStateOf<Progress?>(null) }
    // A run changes the file list it was planned from; re-planning then would only replace the plan it ran.
    LaunchedEffect(files) {
        if (isRunning || result != null) return@LaunchedEffect
        try {
            plan = reimports.planAll(files, onProgress = { planProgress = it })
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.e(expected) { "Re-import all preview failed: ${expected.message}" }
            errorMessage = "Failed to prepare re-import: ${expected.message}"
        }
    }
    val ready = plan?.let { (!it.needsAccount || accountId != null) && (!it.needsCurrency || currencyId != null) } == true

    BulkImportDialogScaffold(
        pendingTitle = "Re-import all imported files",
        completeTitle = "Re-import complete",
        result = result,
        isRunning = isRunning,
        confirmLabel = "Re-import ${files.size} file${if (files.size == 1) "" else "s"}",
        confirmEnabled = ready,
        onRun = {
            val planned = plan ?: return@BulkImportDialogScaffold
            isRunning = true
            errorMessage = null
            scope.launch {
                try {
                    result = reimports.reimportAll(planned, accountId, currencyId, onProgress = { progress = it })
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: Exception) {
                    logger.e(expected) { "Re-import all failed: ${expected.message}" }
                    errorMessage = "Re-import failed: ${expected.message}"
                } finally {
                    isRunning = false
                }
            }
        },
        onDismiss = onDismiss,
        onComplete = onComplete,
        report = { finished ->
            Text(finished.toSummary(), style = MaterialTheme.typography.bodyMedium)
            UnmatchedEditsReport(finished.unmatchedEdits, accountName)
        },
    ) {
        Text(
            text =
                "Deletes what the ${files.size} imported file${if (files.size == 1) "" else "s"} created and " +
                    "imports them again with the current strategies and account mappings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (plan == null && errorMessage == null) {
            ReimportProgressIndicator(planProgress ?: Progress(PREPARING_REIMPORT))
        }
        plan?.let { planned ->
            if (planned.skippedNoStrategy > 0) {
                Text(
                    text =
                        "${planned.skippedNoStrategy} file${if (planned.skippedNoStrategy == 1) "" else "s"} " +
                            "have no matching strategy and will be skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            ReimportSettingsPickers(
                needsAccount = planned.needsAccount,
                needsCurrency = planned.needsCurrency,
                accountId = accountId,
                onAccountSelected = { accountId = it },
                currencyId = currencyId,
                onCurrencySelected = { currencyId = it },
                pickers = pickers,
                enabled = !isRunning,
            )
            ManualEditsSection(
                title = "Manual edits to re-apply (${planned.manualEdits.size}):",
                edits = planned.manualEdits,
                accountName = accountName,
                note = "Edits whose transaction isn't imported again are dropped and listed afterwards.",
            )
        }
        progress?.let {
            Spacer(modifier = Modifier.height(12.dp))
            BulkImportProgressIndicator(it)
        }
    }
}

// Asks only for what the files' own transactions don't say.
@Suppress("LongParameterList")
@Composable
private fun ReimportSettingsPickers(
    needsAccount: Boolean,
    needsCurrency: Boolean,
    accountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    currencyId: CurrencyId?,
    onCurrencySelected: (CurrencyId) -> Unit,
    pickers: ReimportPickerRepositories,
    enabled: Boolean,
) {
    if (needsAccount) {
        AccountPicker(
            selectedAccountId = accountId,
            onAccountSelected = onAccountSelected,
            label = "Source Account",
            accountRepository = pickers.accountRepository,
            categoryRepository = pickers.categoryRepository,
            personRepository = pickers.personRepository,
            enabled = enabled,
            isError = accountId == null,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (needsCurrency) {
        CurrencyPicker(
            selectedCurrencyId = currencyId,
            onCurrencySelected = onCurrencySelected,
            label = "Currency",
            currencyRepository = pickers.currencyRepository,
            enabled = enabled,
            isError = currencyId == null,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

private const val PREPARING_REIMPORT = "Preparing re-import"
