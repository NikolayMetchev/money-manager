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
import com.moneymanager.importengineapi.ImportPlan
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.sections
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.util.formatAmount

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
 * [limit] with an "…and N more" tail when one is given).
 */
@Composable
fun ReimportPlanSection(
    title: String,
    lines: List<String>,
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
}

/**
 * The preview every re-import dialog shows (staged files and API sessions): what the source created and will create
 * again. [recordNoun] names the source's records
 * ("row", "record", "item").
 */
@Composable
fun SourceRerunPreview(
    plan: SourceRerunPlan,
    recordNoun: String,
) {
    Column {
        val transfers = plan.createdTransfers.size
        val trades = plan.tradeIds.size
        Text(
            text =
                buildString {
                    append(
                        "Imports every $recordNoun again with the current strategy and mappings. The $transfers transaction${plural(
                            transfers,
                        )}",
                    )
                    if (trades > 0) append(" and $trades trade${plural(trades)}")
                    append(
                        " it created ${if (transfers + trades == 1) "keeps its id" else "keep their ids"}, and only what changed is written.",
                    )
                },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text =
                "Each $recordNoun takes its transactions' ids back, so hand edits and links on them stay. One no " +
                    "$recordNoun makes any more is listed afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts created by this import that end up with no transactions will be deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun plural(count: Int) = if (count == 1) "" else "s"

/** Names accounts for [ImportPlanReport], falling back to the id while the list loads. */
@Composable
fun rememberAccountNamer(accountRepository: AccountReadRepository): (AccountId) -> String {
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }
    return remember(accounts) {
        val names = accounts.associate { it.id to it.name }
        return@remember { id: AccountId -> names[id] ?: "account ${id.id}" }
    }
}

/**
 * What a re-import did, as [ImportPlan.sections]: the transactions it created, changed (field by field) and removed, the hand
 * edits a new import value met, and the overrides it released ("no changes" when it did nothing).
 */
@Composable
fun ImportPlanReport(
    plan: ImportPlan,
    accountName: (AccountId) -> String,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = plan.summary(), style = MaterialTheme.typography.bodyMedium)
    plan.sections(accountName, amount = ::formatAmount).forEach { section ->
        ReimportPlanSection(
            title = section.title,
            lines = section.lines,
            limit = REIMPORT_PREVIEW_LIMIT,
            isError = section.needsAttention,
        )
    }
}
