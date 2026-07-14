package com.moneymanager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.StrategyLibrary
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.strategycatalog.CatalogItem
import com.moneymanager.strategycatalog.CatalogItemStatus
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/** An install paused on a dialog: reference resolution (unresolved refs) or overwrite confirmation. */
private data class PendingInstall(
    val keys: Set<StrategyKey>,
    val unresolvedByKey: Map<StrategyKey, List<CsvUnresolvedReference>>,
    val overwrites: List<CatalogItem>,
)

/**
 * The central strategy catalog: browse the strategies published to the project's GitHub Pages library
 * and selectively install them into this database (create-or-update by name, via the import engine).
 * Fresh databases ship with no strategies — this screen is where the built-ins come from.
 */
@Composable
@Suppress("LongMethod", "LongParameterList")
fun StrategyCatalogScreen(
    controller: StrategyCatalogController,
    library: StrategyLibrary,
    appVersion: AppVersion,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    initialKindFilter: StrategyKind? = null,
    // Cleared when the screen is embedded somewhere that owns navigation itself (the setup wizard).
    showBackAction: Boolean = true,
    onBack: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val state by controller.state.collectAsState()
    var kindFilter by remember { mutableStateOf(initialKindFilter) }
    var pendingInstall by remember { mutableStateOf<PendingInstall?>(null) }

    LaunchedEffect(Unit) {
        controller.beginBusy()
        controller.refresh(library, appVersion)
    }

    val filteredItems = state.items.filter { kindFilter == null || it.entry.kind == kindFilter }

    fun runInstall(
        keys: Set<StrategyKey>,
        resolutions: Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>>,
    ) {
        scope.launch {
            controller.beginBusy()
            runCatching { controller.install(library, appVersion, keys, resolutions) }
        }
    }

    // Preview first: collect unresolved references and which installs would overwrite local artifacts,
    // then walk the dialogs (overwrite confirm → resolution) before actually installing.
    fun beginInstall(keys: Set<StrategyKey>) {
        scope.launch {
            controller.beginBusy()
            runCatching {
                val unresolved =
                    controller
                        .previewInstall(library, keys)
                        .filter { it.unresolvedReferences.isNotEmpty() }
                        .associate { it.key to it.unresolvedReferences }
                val overwrites = state.items.filter { it.entry.key in keys && it.status != CatalogItemStatus.NOT_INSTALLED }
                if (unresolved.isEmpty() && overwrites.isEmpty()) {
                    controller.install(library, appVersion, keys)
                } else {
                    pendingInstall = PendingInstall(keys, unresolved, overwrites)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Strategy catalog", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Community library of import strategies and pass-through definitions. " +
                        "Installing never deletes anything; updates overwrite the same-named strategy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showBackAction) {
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text("All") })
            for ((kind, label) in kindFilterChips) {
                FilterChip(selected = kindFilter == kind, onClick = { kindFilter = kind }, label = { Text(label) })
            }
        }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Couldn't load the catalog: $error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            controller.beginBusy()
                            controller.refresh(library, appVersion)
                        }
                    }) { Text("Retry") }
                }
            }
        }

        val installable = filteredItems.filter { it.status != CatalogItemStatus.INSTALLED }
        if (installable.isNotEmpty()) {
            Button(
                enabled = !state.busy,
                onClick = { beginInstall(installable.map { it.entry.key }.toSet()) },
            ) { Text("Install all (${installable.size})") }
        }

        if (!state.loaded && state.error == null && !state.busy) {
            Text("The catalog is empty.", fontStyle = FontStyle.Italic)
        }

        // weight(1f): without it the list is measured last with whatever height the header and chips
        // leave over, which on a phone is nothing.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filteredItems, key = { "${it.entry.kind}:${it.entry.name}" }) { item ->
                CatalogRow(
                    item = item,
                    busy = state.busy,
                    onInstall = { beginInstall(setOf(item.entry.key)) },
                )
            }
        }
    }

    pendingInstall?.let { pending ->
        if (pending.overwrites.isNotEmpty()) {
            // Confirm overwrites first; resolution (if any) follows.
            AlertDialog(
                onDismissRequest = { pendingInstall = null },
                title = { Text("Overwrite installed strategies?") },
                text = {
                    Text(
                        "Already installed and will be overwritten with the catalog version " +
                            "(local edits to them are lost):\n" +
                            pending.overwrites.joinToString("\n") { "• ${it.entry.name}" },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { pendingInstall = pending.copy(overwrites = emptyList()) }) { Text("Overwrite") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingInstall = null }) { Text("Cancel") }
                },
            )
        } else if (pending.unresolvedByKey.isNotEmpty()) {
            StrategyPullResolutionDialog(
                unresolvedByKey = pending.unresolvedByKey,
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                currencyRepository = currencyRepository,
                personRepository = personRepository,
                onConfirm = { resolutions ->
                    pendingInstall = null
                    runInstall(pending.keys, resolutions)
                },
                onDismiss = { pendingInstall = null },
            )
        } else {
            // Overwrites confirmed and nothing to resolve: install directly.
            LaunchedEffect(pending) {
                pendingInstall = null
                runInstall(pending.keys, emptyMap())
            }
        }
    }
}

@Composable
private fun CatalogRow(
    item: CatalogItem,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.entry.kind.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(0.05f))
            when (item.status) {
                CatalogItemStatus.INSTALLED ->
                    Text(
                        "Installed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                CatalogItemStatus.NOT_INSTALLED ->
                    Button(enabled = !busy, onClick = onInstall) { Text("Install") }
                CatalogItemStatus.UPDATE_AVAILABLE ->
                    OutlinedButton(enabled = !busy, onClick = onInstall) { Text("Update") }
            }
        }
    }
}

private val kindFilterChips =
    listOf(
        StrategyKind.CSV to "CSV",
        StrategyKind.QIF to "QIF",
        StrategyKind.API to "API",
        StrategyKind.PASS_THROUGH to "Pass-through",
    )

private fun StrategyKind.displayName(): String =
    when (this) {
        StrategyKind.CSV -> "CSV import strategy"
        StrategyKind.QIF -> "QIF import strategy"
        StrategyKind.API -> "API import strategy"
        StrategyKind.PASS_THROUGH -> "Pass-through definition"
        StrategyKind.GLOBAL_MAPPINGS -> "Global account mappings"
    }
