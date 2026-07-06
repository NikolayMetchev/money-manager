package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.ReimportMerge
import com.moneymanager.csvimporter.ReimportReversal
import com.moneymanager.csvimporter.ReimportSkippedAccount

/**
 * Lists the duplicate-account merges a bulk re-import performed, the prior merges it reversed (split
 * back out), and anything it could not act on, so the bulk "Re-import all" flow shows WHICH accounts
 * changed — the per-file preview's key detail. Shared by the CSV and QIF re-import-all dialogs (the QIF
 * UI depends on the CSV UI). Renders nothing when all three lists are empty.
 */
@Composable
fun BulkMergeReport(
    merges: List<ReimportMerge>,
    reversals: List<ReimportReversal>,
    skipped: List<ReimportSkippedAccount>,
) {
    if (merges.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts merged:",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column {
            merges.forEach { merge ->
                Text(
                    text = "• ${merge.duplicateName} → ${merge.targetName} (${merge.transferCount} transaction(s))",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (reversals.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Merges reversed (accounts split back out):",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column {
            reversals.forEach { reversal ->
                Text(
                    text = "• ${reversal.deletedAccountName} ← ${reversal.survivingName} (${reversal.transferCount} transaction(s))",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (skipped.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Not merged/updated:",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column {
            skipped.forEach { skip ->
                Text(
                    text = "• ${skip.accountName}: ${skip.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
