package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.importengineapi.ImportProgress

/** How many bullets a preview section lists before collapsing the rest into an "…and N more" line. */
const val REIMPORT_PREVIEW_LIMIT = 20

/**
 * Phase label (plus "x of y" when counts are known) over a linear progress bar — determinate when
 * the phase reports a fraction, indeterminate otherwise.
 */
@Composable
fun ReimportProgressIndicator(progress: ImportProgress) {
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
 * The read-only preview shared by the CSV and QIF re-import dialogs: the account merges/reversals the
 * current mappings imply, the transactions whose values the strategy now computes differently, and the
 * accounts that will be cleaned up.
 *
 * The two flows differ only in wording and in the source-specific sections, so [valueUpdateNote] and
 * [pendingItemsNote] carry the row-vs-record phrasing and [extraSections] renders whatever else the
 * caller's plan can contain (the CSV rewrite/trade/reconcile sections) between the value updates and the
 * "not merged" list.
 */
@Composable
fun ReimportPlanPreview(
    plan: ReimportPlan,
    hasPendingItems: Boolean,
    valueUpdateNote: String,
    pendingItemsNote: String,
    extraSections: @Composable ColumnScope.() -> Unit = {},
) {
    Column {
        if (plan.merges.isNotEmpty()) {
            ReimportPlanSection(
                title = "Duplicate accounts to merge:",
                lines =
                    plan.merges.map { merge ->
                        "${merge.duplicateName} → ${merge.targetName} (${merge.transferCount} transaction(s))"
                    },
                note =
                    "Merges move ALL of the duplicate's transactions (from any import) and can be undone " +
                        "from the account merge history.",
                leadingSpacer = false,
            )
        } else {
            Text(
                text = "No duplicate accounts to merge under the current account mappings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.reversals.isNotEmpty()) {
            ReimportPlanSection(
                title = "Merges to reverse (accounts to split back out):",
                lines =
                    plan.reversals.map { reversal ->
                        "${reversal.deletedAccountName} ← ${reversal.survivingName} (${reversal.transferCount} transaction(s))"
                    },
                note =
                    "The current mappings no longer consolidate these onto the survivor, so the earlier merge " +
                        "is undone — the account is recreated and its transactions move back.",
            )
        }

        if (plan.valueUpdates.isNotEmpty()) {
            ReimportPlanSection(
                title = "Transactions to update to the strategy's current values (${plan.valueUpdates.size}):",
                lines = plan.valueUpdates.map { update -> "${update.description}: ${update.changes.joinToString("; ")}" },
                note = valueUpdateNote,
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }

        extraSections()

        if (plan.skipped.isNotEmpty()) {
            ReimportPlanSection(
                title = "Not merged:",
                lines = plan.skipped.map { skip -> "${skip.accountName}: ${skip.detail}" },
                isError = true,
            )
        }

        if (hasPendingItems) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = pendingItemsNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts created by this import that end up with no transactions will be deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
