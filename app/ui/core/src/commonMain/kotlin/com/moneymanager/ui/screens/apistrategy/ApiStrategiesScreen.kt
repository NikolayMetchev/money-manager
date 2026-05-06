@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import kotlinx.coroutines.launch

@Composable
fun ApiStrategiesScreen(
    apiImportStrategyRepository: ApiImportStrategyRepository,
    onBack: () -> Unit = {},
) {
    val strategies by apiImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var strategyToEdit by remember { mutableStateOf<ApiImportStrategy?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(
                    text = "API Import Strategies",
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = { showCreateDialog = true }) { Text("+ New") }
            }

            if (strategies.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No API import strategies yet.\nClick '+ New' to create one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(strategies) { strategy ->
                            StrategyCard(
                                strategy = strategy,
                                onClick = { strategyToEdit = strategy },
                                onDelete = {
                                    scope.launch {
                                        apiImportStrategyRepository.deleteStrategy(strategy.id)
                                    }
                                },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        ApiStrategyEditDialog(
            strategy = null,
            onSave = { newStrategy ->
                scope.launch {
                    apiImportStrategyRepository.createStrategy(newStrategy)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    strategyToEdit?.let { strategy ->
        ApiStrategyEditDialog(
            strategy = strategy,
            onSave = { updated ->
                scope.launch {
                    apiImportStrategyRepository.updateStrategy(updated)
                }
                strategyToEdit = null
            },
            onDismiss = { strategyToEdit = null },
        )
    }
}

@Composable
private fun StrategyCard(
    strategy: ApiImportStrategy,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = strategy.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = "Base URL: ${strategy.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Auth: ${strategy.authType.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Accounts: ${strategy.accountsEndpoint.path}  |  Transactions: ${strategy.transactionsEndpoint.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Edit")
            }
        }
    }
}
