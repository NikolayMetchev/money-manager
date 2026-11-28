package com.moneymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.session.SessionImports
import com.moneymanager.session.StrategyReimport
import com.moneymanager.session.findAccountMappingReimport
import com.moneymanager.session.findStrategyReimports
import com.moneymanager.session.reimportUpdatedStrategies
import com.moneymanager.tasks.TaskManager
import com.moneymanager.ui.screens.accountmapping.OnAccountMappingSaved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What saving a strategy or an account mapping offers to re-import; each finds its own previous imports. */
internal class ReimportOffers(
    val onStrategiesUpdated: (Set<StrategyKey>) -> Unit,
    val onAccountMappingSaved: OnAccountMappingSaved,
)

/** An offer waiting for an answer: the imports to re-run and, for a mapping, the accounts it takes over. */
private data class PendingReimport(
    val reimport: StrategyReimport,
    val capturedAccounts: List<String> = emptyList(),
)

/** Hosts the offers above navigation so saving in an editor can still return to the screen it came from. */
@Composable
internal fun rememberReimportOffers(
    imports: SessionImports,
    tasks: TaskManager,
): ReimportOffers {
    val scope = rememberSchemaAwareCoroutineScope()
    var pending by remember(imports) { mutableStateOf<PendingReimport?>(null) }
    var error by remember(imports) { mutableStateOf<String?>(null) }
    pending?.let { offer ->
        ReimportOfferDialog(offer, onDismiss = { pending = null }, onReimport = {
            val task =
                tasks.start("strategy-reimport", "Re-import previous imports", "Preparing previous imports") {
                    imports.reimportUpdatedStrategies(offer.reimport, this)
                }
            if (task != null) pending = null else error = "A re-import is already running. Try again when it finishes."
        })
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Could not offer a re-import") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
    return remember(imports, scope) {
        fun offer(find: suspend () -> PendingReimport?) {
            scope.launch {
                try {
                    val found = find() ?: return@launch
                    val previous = pending
                    pending =
                        PendingReimport(
                            reimport =
                                StrategyReimport(
                                    files = (previous?.reimport?.files.orEmpty() + found.reimport.files).distinctBy { it.id },
                                    sessions = previous?.reimport?.sessions.orEmpty() + found.reimport.sessions,
                                ),
                            capturedAccounts = (previous?.capturedAccounts.orEmpty() + found.capturedAccounts).distinct(),
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    error = "Could not find previous imports: ${expected.message}"
                }
            }
        }
        ReimportOffers(
            onStrategiesUpdated = { keys ->
                offer { imports.findStrategyReimports(keys).takeUnless { it.isEmpty }?.let(::PendingReimport) }
            },
            onAccountMappingSaved = { pattern, accountId, strategyId ->
                offer {
                    imports
                        .findAccountMappingReimport(pattern, accountId, strategyId)
                        .takeUnless { it.isEmpty }
                        ?.let { found -> PendingReimport(found.reimport, found.accounts.map { it.name }) }
                }
            },
        )
    }
}

@Composable
private fun ReimportOfferDialog(
    offer: PendingReimport,
    onDismiss: () -> Unit,
    onReimport: () -> Unit,
) {
    val sources =
        listOfNotNull(
            offer.reimport.files.size
                .takeIf { it > 0 }
                ?.let { "$it file${if (it == 1) "" else "s"}" },
            offer.reimport.sessions.size
                .takeIf { it > 0 }
                ?.let { "$it API session${if (it == 1) "" else "s"}" },
        ).joinToString(" and ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-import previous imports?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offer.capturedAccounts.isEmpty()) {
                    Text("Apply the updated strategies to $sources already imported?")
                } else {
                    val count = offer.capturedAccounts.size
                    Text(
                        "The mapping takes over $count existing account${if (count == 1) "" else "s"}. " +
                            "Re-import the $sources that booked onto them to move their rows to the mapped account?",
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    ) {
                        offer.capturedAccounts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onReimport) { Text("Re-import all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
