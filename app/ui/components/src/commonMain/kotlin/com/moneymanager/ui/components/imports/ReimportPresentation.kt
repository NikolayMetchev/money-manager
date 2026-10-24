package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.tasks.Progress

/** How many bullets a preview section lists before collapsing the rest into an "…and N more" line. */
const val REIMPORT_PREVIEW_LIMIT = 20

/**
 * Phase label (plus "x of y" when counts are known) over a linear progress bar — determinate when
 * the phase reports a fraction, indeterminate otherwise.
 */
@Composable
fun ReimportProgressIndicator(progress: Progress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val counts =
            progress.processed
                ?.let { processed ->
                    progress.total?.let { total -> " — $processed of $total" }
                }.orEmpty()
        Text(
            text = "${progress.detail}$counts…",
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

/**
 * One titled block of the re-import preview: a heading, a bullet per [lines] entry (truncated to
 * [limit] with an "…and N more" tail when one is given) and an optional explanatory [note].
 */
@Suppress("LongParameterList")
@Composable
fun ReimportPlanSection(
    title: String,
    lines: List<String>,
    note: String? = null,
    limit: Int? = null,
    isError: Boolean = false,
    leadingSpacer: Boolean = true,
) {
    if (leadingSpacer) {
        Spacer(modifier = Modifier.height(12.dp))
    }
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    Spacer(modifier = Modifier.height(4.dp))
    val shown = if (limit != null) lines.take(limit) else lines
    shown.forEach { line ->
        Text(
            text = "• $line",
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    if (limit != null && lines.size > limit) {
        Text(
            text = "…and ${lines.size - limit} more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (note != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The preview every re-import dialog shows (CSV, QIF and API): what the source created and will create
 * again, the other files' rows matched against it, which are re-run too, and the hand edits that will be
 * put back. [recordNoun] names the source's records ("row", "record", "item").
 */
@Composable
fun SourceRerunPreview(
    plan: SourceRerunPlan,
    recordNoun: String,
    accountName: (AccountId) -> String,
) {
    Column {
        val transfers = plan.createdTransfers.size
        val trades = plan.tradeIds.size
        Text(
            text =
                buildString {
                    append("Deletes the $transfers transaction${plural(transfers)}")
                    if (trades > 0) append(" and $trades trade${plural(trades)}")
                    append(" it created, then imports every $recordNoun again with the current strategy and mappings.")
                },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!plan.affected.isEmpty) {
            val files = plan.affected.csvRows.size + plan.affected.qifRecords.size
            ReimportPlanSection(
                title = "Rows of other files to re-run (${plan.affected.count}):",
                lines = emptyList(),
                note =
                    "${plan.affected.count} row${plural(plan.affected.count)} of $files other file${plural(files)} " +
                        "were reconciled against these transactions. They are imported again too, so they " +
                        "reconcile against the new ones.",
            )
        }
        ManualEditsSection(
            title = "Manual edits to re-apply (${plan.manualEdits.size}):",
            edits = plan.manualEdits,
            accountName = accountName,
            note =
                "Each edit is put back on the transaction its $recordNoun creates again. One whose " +
                    "$recordNoun no longer creates a matching transaction is dropped and listed afterwards.",
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts created by this import that end up with no transactions will be deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lists hand edits, one bullet per transaction with its changes; nothing when [edits] is empty. */
@Composable
fun ManualEditsSection(
    title: String,
    edits: List<ManualEdit>,
    accountName: (AccountId) -> String,
    note: String? = null,
    isError: Boolean = false,
) {
    if (edits.isEmpty()) return
    ReimportPlanSection(
        title = title,
        lines = edits.map { edit -> "${edit.imported.description}: ${edit.describeChanges(accountName).joinToString("; ")}" },
        note = note,
        limit = REIMPORT_PREVIEW_LIMIT,
        isError = isError,
    )
}

/** The hand edits a finished re-import could not put back, for its result report. */
@Composable
fun UnmatchedEditsReport(
    edits: List<ManualEdit>,
    accountName: (AccountId) -> String,
) {
    ManualEditsSection(
        title = "Manual edits not re-applied (${edits.size}):",
        edits = edits,
        accountName = accountName,
        note = "The re-import no longer creates a matching transaction for these, so redo them by hand if still needed.",
        isError = true,
    )
}

private fun plural(count: Int) = if (count == 1) "" else "s"

/** Names accounts for [ManualEdit.describeChanges], falling back to the id while the list loads. */
@Composable
fun rememberAccountNamer(accountRepository: AccountReadRepository): (AccountId) -> String {
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }
    return remember(accounts) {
        val names = accounts.associate { it.id to it.name }
        return@remember { id: AccountId -> names[id] ?: "account ${id.id}" }
    }
}

/** One line for a finished re-import of one file or session, given how many of its records [imported]. */
fun SourceRerunResult<*>.toSummary(imported: Int): String =
    buildString {
        append("Re-import complete: $deletedTransfers transaction${plural(deletedTransfers)} deleted")
        if (deletedTrades > 0) append(" and $deletedTrades trade${plural(deletedTrades)}")
        append(", $imported imported")
        if (!affected.isEmpty) append(", ${affected.count} row${plural(affected.count)} of other files re-run")
        if (!notRerun.isEmpty) {
            append(", ${notRerun.count} row${plural(notRerun.count)} of other files left for those files' own re-import")
        }
        if (reappliedEdits.isNotEmpty()) append(", ${reappliedEdits.size} manual edit${plural(reappliedEdits.size)} re-applied")
        if (unmatchedEdits.isNotEmpty()) {
            append(", ${unmatchedEdits.size} manual edit${plural(unmatchedEdits.size)} not re-applied")
        }
        if (deletedEmptyAccounts.isNotEmpty()) {
            append(", ${deletedEmptyAccounts.size} empty account${plural(deletedEmptyAccounts.size)} deleted")
        }
    }
