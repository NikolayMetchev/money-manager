package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.ReimportAllPlan
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.csvimporter.source
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val logger = Logger.withTag("ReimportDialogs")

/** What [ReimportDialog] reads: where each file came from (to name it by its path), and its pickers' lists. */
data class ReimportRepositories(
    val importDirectoryRepository: ImportDirectoryReadRepository,
    val accountRepository: AccountReadRepository,
    val categoryRepository: CategoryReadRepository,
    val currencyRepository: CurrencyReadRepository,
    val personRepository: PersonReadRepository,
)

/**
 * Re-imports the staged [files] (CSV, QIF, OFX, Excel or PDF; one or many, the same dialog either way) so strategy and mapping
 * changes take effect retroactively: what they created is deleted and they are imported again, together with every other
 * file that depends on what they created ([Reimports.planAll]), and the hand edits are put back. Each file keeps the strategy,
 * account and currency it was imported with; the pickers only appear for files that created nothing to read them from.
 * Every file, the cascaded ones too, is named by its path (its import directory, then its name), whichever screen opened
 * the dialog, and Cancel stops the run once the file under way is done.
 */
@Suppress("LongMethod")
@Composable
fun ReimportDialog(
    files: List<CsvImport>,
    reimports: Reimports,
    repositories: ReimportRepositories,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val accountName = rememberAccountNamer(repositories.accountRepository)
    val directoryIds by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        repositories.importDirectoryRepository.csvImportDirectories()
    }
    val directories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        repositories.importDirectoryRepository.getAllDirectories()
    }
    val importDirectories = remember(directoryIds, directories) { resolveImportDirectories(directoryIds, directories) }
    val fileLabel: (CsvImport) -> String = { stagedImportPath(it, importDirectories) }
    var stopRequested by remember { mutableStateOf(false) }

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
        pendingTitle = files.singleOrNull()?.let { "Re-import ${fileLabel(it)}" } ?: "Re-import ${files.size} files",
        completeTitle = "Re-import complete",
        result = result,
        isRunning = isRunning,
        confirmLabel = (plan?.rerunFiles?.size ?: files.size).let { "Re-import $it file${if (it == 1) "" else "s"}" },
        // A file with no strategy is skipped, so a run of nothing but such files has nothing to do.
        confirmEnabled = ready && plan?.rerunFiles?.isNotEmpty() == true,
        onRun = {
            val planned = plan ?: return@BulkImportDialogScaffold
            isRunning = true
            stopRequested = false
            errorMessage = null
            scope.launch {
                try {
                    result =
                        reimports.reimportAll(
                            planned,
                            accountId,
                            currencyId,
                            onProgress = { progress = it },
                            fileLabel = fileLabel,
                            stopRequested = { stopRequested },
                        )
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
            UnmatchedLinksReport(finished.reappliedLinks)
        },
        onStop = { stopRequested = true },
        stopping = stopRequested,
    ) {
        Text(
            text =
                "Deletes what ${if (files.size == 1) "the file" else "the ${files.size} files"} created and imports " +
                    "${if (files.size == 1) "it" else "them"} again with the current strategies and account mappings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (plan == null && errorMessage == null) {
            ReimportProgressIndicator(planProgress ?: Progress(PREPARING_REIMPORT))
        }
        plan?.let { planned ->
            files.singleOrNull()?.let { file ->
                Text(
                    text =
                        planned.strategies[file.source]?.let { "Strategy: ${it.name}" }
                            ?: "No strategy matches this import. Create or restore a matching strategy first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (planned.strategies[file.source] == null) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            val deleted = planned.rerunFiles.sumOf { it.createdTransfers.size }
            Text(
                text = "Deletes the $deleted transaction${if (deleted == 1) "" else "s"} they created.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (planned.cascaded.isNotEmpty()) {
                val count = planned.cascaded.size
                Text(
                    text =
                        "$count other file${if (count == 1) "" else "s"} depend${if (count == 1) "s" else ""} on " +
                            "${if (files.size == 1) "it" else "these"} and will be re-imported too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
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
                repositories = repositories,
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

// Asks only for what the files' last imports didn't record.
@Suppress("LongParameterList")
@Composable
private fun ReimportSettingsPickers(
    needsAccount: Boolean,
    needsCurrency: Boolean,
    accountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    currencyId: CurrencyId?,
    onCurrencySelected: (CurrencyId) -> Unit,
    repositories: ReimportRepositories,
    enabled: Boolean,
) {
    if (needsAccount) {
        AccountPicker(
            selectedAccountId = accountId,
            onAccountSelected = onAccountSelected,
            label = "Source Account",
            accountRepository = repositories.accountRepository,
            categoryRepository = repositories.categoryRepository,
            personRepository = repositories.personRepository,
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
            currencyRepository = repositories.currencyRepository,
            enabled = enabled,
            isError = currencyId == null,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

private const val PREPARING_REIMPORT = "Preparing re-import"
