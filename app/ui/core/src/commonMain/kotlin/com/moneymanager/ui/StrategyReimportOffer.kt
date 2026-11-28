package com.moneymanager.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.session.SessionImports
import com.moneymanager.session.StrategyReimport
import com.moneymanager.session.findStrategyReimports
import com.moneymanager.session.reimportUpdatedStrategies
import com.moneymanager.tasks.TaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Hosts the offer above navigation so saving in an editor can still return to the strategies screen. */
@Composable
internal fun rememberStrategyReimportOffer(
    imports: SessionImports,
    tasks: TaskManager,
): (Set<StrategyKey>) -> Unit {
    val scope = rememberSchemaAwareCoroutineScope()
    var pending by remember(imports) { mutableStateOf<StrategyReimport?>(null) }
    var error by remember(imports) { mutableStateOf<String?>(null) }
    pending?.let { offer ->
        val sources =
            listOfNotNull(
                offer.files.size
                    .takeIf { it > 0 }
                    ?.let { "$it file${if (it == 1) "" else "s"}" },
                offer.sessions.size
                    .takeIf { it > 0 }
                    ?.let { "$it API session${if (it == 1) "" else "s"}" },
            ).joinToString(" and ")
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Re-import previous imports?") },
            text = { Text("Apply the updated strategies to $sources already imported?") },
            confirmButton = {
                TextButton(onClick = {
                    val task =
                        tasks.start("strategy-reimport", "Re-import after strategy update", "Preparing previous imports") {
                            imports.reimportUpdatedStrategies(offer, this)
                        }
                    if (task != null) pending = null else error = "A strategy re-import is already running. Try again when it finishes."
                }) { Text("Re-import all") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Not now") }
            },
        )
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
        { keys ->
            scope.launch {
                try {
                    val offer = imports.findStrategyReimports(keys)
                    if (!offer.isEmpty) {
                        val previous = pending
                        pending =
                            StrategyReimport(
                                files = (previous?.files.orEmpty() + offer.files).distinctBy { it.id },
                                sessions = previous?.sessions.orEmpty() + offer.sessions,
                            )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    error = "Could not find previous imports: ${expected.message}"
                }
            }
        }
    }
}
