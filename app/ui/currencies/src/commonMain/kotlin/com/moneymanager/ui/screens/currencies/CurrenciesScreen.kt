package com.moneymanager.ui.screens.currencies

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.deleteCurrency
import com.moneymanager.ui.components.CreateCurrencyDialog
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.foundation.LocalImportEngine

@Composable
fun CurrenciesScreen(
    currencyRepository: CurrencyReadRepository,
    onAuditClick: (Currency) -> Unit = {},
) {
    val currencies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        currencyRepository.getAllCurrencies()
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                text = "Your Currencies",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (currencies.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No currencies yet. Add your first currency!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(currencies) { currency ->
                            CurrencyCard(
                                currency = currency,
                                onAuditClick = { onAuditClick(currency) },
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

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineLarge)
        }

        if (showCreateDialog) {
            CreateCurrencyDialog(
                onCurrencyCreated = { showCreateDialog = false },
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

@Composable
fun CurrencyCard(
    currency: Currency,
    onAuditClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currency.code,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = currency.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onAuditClick,
            ) {
                Text(
                    text = "📋",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(
                onClick = { showDeleteDialog = true },
            ) {
                Text(
                    text = "🗑️",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteCurrencyDialog(
            currency = currency,
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun DeleteCurrencyDialog(
    currency: Currency,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current

    DestructiveConfirmDialog(
        title = "Delete Currency?",
        targetName = "${currency.code} - ${currency.name}",
        consequence = "This action cannot be undone. All accounts using this currency will be affected.",
        failureMessage = "Failed to delete currency",
        onConfirm = {
            importEngine.deleteCurrency(currency.id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
