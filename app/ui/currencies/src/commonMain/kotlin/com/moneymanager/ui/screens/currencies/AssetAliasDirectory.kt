package com.moneymanager.ui.screens.currencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AssetAlias
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.importengineapi.removeAssetAlias
import com.moneymanager.importengineapi.setAssetAlias
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.launch

/** What the alias rows of both asset tabs show: the database's own aliases, and the codes that are assets themselves. */
internal class AssetAliasDirectory(
    aliases: List<AssetAlias>,
    private val assetCodes: Set<String>,
) {
    private val stored = aliases.map { it.alias }.toSet()
    private val byAsset = aliases.groupBy({ it.assetCode }, { it.alias })

    /** The database's own aliases of the asset with [code]; a person can remove these. */
    fun storedAliases(code: String): List<String> = byAsset[code.uppercase()].orEmpty()

    /** The catalog's aliases of the asset with [code] that do apply: a code that's an asset itself keeps that asset. */
    fun catalogAliases(code: String): List<String> = CryptoRegistry.catalogAliasesOf(code).filter { it !in stored && it !in assetCodes }

    fun allAliases(code: String): List<String> = storedAliases(code) + catalogAliases(code)
}

/** The aliases of the asset with [code], with an action per alias a person can change and one to add another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssetAliasRow(
    code: String,
    directory: AssetAliasDirectory,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            directory.storedAliases(code).forEach { alias ->
                AssistChip(
                    onClick = {
                        scope.launch {
                            runCatching { importEngine.removeAssetAlias(alias) }
                                .onFailure { error = "Failed to remove $alias: ${it.message}" }
                        }
                    },
                    label = { Text("$alias ✕") },
                )
            }
            directory.catalogAliases(code).forEach { alias ->
                AssistChip(onClick = {}, enabled = false, label = { Text("$alias (catalog)") })
            }
            TextButton(onClick = { adding = true }) { Text("Add alias") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (adding) {
        AddAssetAliasDialog(code = code, onDismiss = { adding = false })
    }
}

@Composable
private fun AddAssetAliasDialog(
    code: String,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberCoroutineScope()
    var alias by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an alias of $code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Imports will book this code as $code. If it's another asset's code, re-import to move what's " +
                        "already booked; that asset then stays empty until you delete it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = alias.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { importEngine.setAssetAlias(alias, code) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
